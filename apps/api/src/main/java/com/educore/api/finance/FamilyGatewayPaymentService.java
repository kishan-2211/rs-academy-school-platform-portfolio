package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.finance.FamilyGatewayPaymentModels.Availability;
import com.educore.api.finance.FamilyGatewayPaymentModels.Continuation;
import com.educore.api.finance.FamilyGatewayPaymentModels.History;
import com.educore.api.finance.FamilyGatewayPaymentModels.InitiateRequest;
import com.educore.api.finance.FamilyGatewayPaymentModels.Payment;
import com.educore.api.finance.FamilyGatewayPaymentModels.Receipt;
import com.educore.api.finance.FamilyGatewayPaymentModels.SelectedCharge;
import com.educore.api.finance.FamilyGatewayPaymentModels.Status;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Family intent/status boundary, not provider I/O or accounting authority.
 * Reservations and their durable enqueue commit together under the real
 * guardian actor. Strong financial evidence always outranks browser/callback
 * labels; expired intents never release held obligations automatically.
 */
@Service
public class FamilyGatewayPaymentService {
    private static final int PAGE_SIZE = 20;
    // Association can become known after an immutable NULL-attempt fact was
    // retained. Follow only this exact scoped provider/checkout's observed order
    // identities; never rewrite the old fact or guess a student from a callback.
    private static final String RELATED_CAPTURE = """
            capture.organisation_id=attempt.organisation_id AND capture.school_id=attempt.school_id
              AND capture.payment_provider_config_id=attempt.payment_provider_config_id
              AND capture.provider_code=attempt.provider_code AND capture.provider_environment=attempt.provider_environment
              AND capture.merchant_account_reference=attempt.merchant_account_reference
              AND (capture.attempt_id=attempt.id OR capture.checkout_reference=attempt.checkout_reference::text
                OR EXISTS(SELECT 1 FROM fee_gateway_checkout_observations related WHERE related.attempt_id=attempt.id
                  AND related.organisation_id=attempt.organisation_id AND related.school_id=attempt.school_id
                  AND related.payment_provider_config_id=attempt.payment_provider_config_id
                  AND related.provider_checkout_reference=attempt.checkout_reference AND related.attempt_reference=attempt.id
                  AND capture.order_reference=ANY(related.order_references)))
            """;
    private static final String RELATED_PAYMENT = """
            observation.organisation_id=attempt.organisation_id AND observation.school_id=attempt.school_id
              AND observation.payment_provider_config_id=attempt.payment_provider_config_id
              AND observation.provider_code=attempt.provider_code AND observation.provider_environment=attempt.provider_environment
              AND observation.merchant_account_reference=attempt.merchant_account_reference
              AND (observation.attempt_id=attempt.id OR EXISTS(SELECT 1 FROM fee_gateway_checkout_observations related
                WHERE related.attempt_id=attempt.id AND related.organisation_id=attempt.organisation_id AND related.school_id=attempt.school_id
                  AND related.payment_provider_config_id=attempt.payment_provider_config_id
                  AND related.provider_checkout_reference=attempt.checkout_reference AND related.attempt_reference=attempt.id
                  AND observation.order_reference=ANY(related.order_references)))
            """;
    private static final String ATTEMPTS = """
            SELECT attempt.id,attempt.organisation_id,attempt.school_id,attempt.learner_fee_account_id,
              account.learner_id,session.name AS session_name,attempt.amount,attempt.currency_code,attempt.status,
              attempt.created_at,attempt.expires_at,attempt.original_business_date,attempt.initiated_by_user_id,
              attempt.payment_provider_config_id,attempt.provider_code,attempt.provider_environment,attempt.merchant_account_reference,
              EXISTS(SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
                AND allocation.organisation_id=attempt.organisation_id AND allocation.school_id=attempt.school_id
                AND allocation.learner_fee_account_id=account.id AND allocation.status='ACTIVE') AS reservation_active,
              receipt.id AS receipt_id,receipt.receipt_number,receipt.received_on AS receipt_posting_on,
              (EXISTS(SELECT 1 FROM fee_gateway_captures capture WHERE
            """ + RELATED_CAPTURE + """
               ) OR EXISTS(SELECT 1 FROM fee_gateway_payment_observations observation WHERE
            """ + RELATED_PAYMENT + """
                AND (observation.captured OR observation.payment_status='SUCCESS'))
               OR EXISTS(SELECT 1 FROM fee_gateway_checkout_observations observation WHERE observation.attempt_id=attempt.id
                AND observation.organisation_id=attempt.organisation_id AND observation.school_id=attempt.school_id
                AND (observation.amount_paid>0 OR observation.successful_payment_count>0 OR observation.checkout_status IN ('PAID','PARTIALLY_PAID')))
               OR EXISTS(SELECT 1 FROM fee_gateway_attempt_work work WHERE work.attempt_id=attempt.id
                AND work.organisation_id=attempt.organisation_id AND work.school_id=attempt.school_id
                AND work.status IN ('COMPLETE','REVIEW_REQUIRED'))) AS review_evidence,
              (EXISTS(SELECT 1 FROM fee_gateway_captures capture WHERE
            """ + RELATED_CAPTURE + """
                AND (receipt.gateway_capture_id IS NULL OR capture.id<>receipt.gateway_capture_id))
               OR EXISTS(SELECT 1 FROM fee_gateway_payment_observations observation WHERE
            """ + RELATED_PAYMENT + """
                AND (observation.captured OR observation.payment_status='SUCCESS') AND NOT EXISTS(SELECT 1 FROM fee_gateway_captures original
                  WHERE original.id=receipt.gateway_capture_id AND original.organisation_id=attempt.organisation_id
                    AND original.school_id=attempt.school_id AND original.payment_reference=observation.payment_reference
                    AND original.order_reference=observation.order_reference AND original.amount=observation.amount
                    AND original.currency_code=observation.currency_code AND observation.captured AND observation.payment_status='SUCCESS'
                    AND original.paid_at=observation.provider_paid_at
                    AND original.paid_at_exact IS NOT DISTINCT FROM observation.provider_paid_at_exact))
               OR EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions resolution JOIN fee_gateway_captures capture
                ON capture.id=resolution.capture_id AND capture.organisation_id=resolution.organisation_id AND capture.school_id=resolution.school_id
                WHERE
            """ + RELATED_CAPTURE + """
                  AND (resolution.resolution_type='REFUND_REQUIRED' OR resolution.resolution_type='HOLD'
                    AND NOT EXISTS(SELECT 1 FROM fee_gateway_capture_resolutions posted WHERE posted.capture_id=capture.id
                      AND posted.organisation_id=capture.organisation_id AND posted.school_id=capture.school_id
                      AND posted.resolution_type='POSTED')))) AS additional_review_evidence
            FROM fee_gateway_attempts attempt JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id
              AND account.organisation_id=attempt.organisation_id AND account.school_id=attempt.school_id
            JOIN academic_sessions session ON session.id=account.academic_session_id
              AND session.organisation_id=account.organisation_id AND session.school_id=account.school_id
            LEFT JOIN fee_payments receipt ON receipt.id=attempt.fee_payment_id AND attempt.status='POSTED'
              AND receipt.organisation_id=attempt.organisation_id AND receipt.school_id=attempt.school_id
              AND receipt.learner_fee_account_id=account.id AND receipt.amount=attempt.amount
              AND receipt.payment_method='ONLINE_PROVIDER' AND receipt.status='POSTED'
              AND EXISTS(SELECT 1 FROM fee_gateway_captures capture
                JOIN fee_gateway_capture_resolutions resolution ON resolution.capture_id=capture.id
                  AND resolution.organisation_id=capture.organisation_id AND resolution.school_id=capture.school_id
                WHERE capture.id=receipt.gateway_capture_id AND capture.attempt_id=attempt.id
                  AND capture.organisation_id=attempt.organisation_id AND capture.school_id=attempt.school_id
                  AND capture.amount=attempt.amount AND capture.currency_code=attempt.currency_code
                  AND resolution.resolution_type='POSTED' AND resolution.fee_payment_id=receipt.id
                  AND gateway_receipt_evidence_matches(capture.id,attempt.organisation_id,attempt.school_id))
            WHERE attempt.organisation_id=:organisationId AND current_app_organisation_id()=:organisationId
              AND current_app_user_id()=:actorUserId
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final FamilyGatewayAccess access;
    private final GatewayReservationService reservations;
    private final GatewayWorkQueue queue;
    private final GatewayProviderRegistry registry;
    private final FamilyGatewayCheckoutService checkout;

    public FamilyGatewayPaymentService(NamedParameterJdbcTemplate jdbc, FamilyGatewayAccess access,
            GatewayReservationService reservations, GatewayWorkQueue queue, GatewayProviderRegistry registry,
            FamilyGatewayCheckoutService checkout) {
        this.jdbc = Objects.requireNonNull(jdbc); this.access = Objects.requireNonNull(access);
        this.reservations = Objects.requireNonNull(reservations); this.queue = Objects.requireNonNull(queue);
        this.registry = Objects.requireNonNull(registry); this.checkout = Objects.requireNonNull(checkout);
    }

    @Transactional(readOnly = true)
    public History history(AuthenticatedUser user, UUID learnerId, int page) {
        requireContext(user, false);
        if (page < 0 || page > 10000) throw invalid("Choose a valid payment-history page.");
        var scope = access.requireChild(user, learnerId);
        var p = parameters(user, scope).addValue("limit", PAGE_SIZE + 1).addValue("offset", Math.multiplyExact(page, PAGE_SIZE));
        var attempts = jdbc.query(ATTEMPTS + " AND attempt.school_id=:schoolId AND account.learner_id=:learnerId ORDER BY attempt.created_at DESC,attempt.id DESC LIMIT :limit OFFSET :offset",
                p, (row, n) -> map(row));
        var payments = new ArrayList<Payment>();
        Instant now = databaseNow(p);
        for (var attempt : attempts.subList(0, Math.min(attempts.size(), PAGE_SIZE))) {
            access.requireAccount(user, learnerId, attempt.accountId());
            payments.add(payment(user, scope, attempt, now));
        }
        return new History(learnerId, route(scope).availability(), page, PAGE_SIZE, attempts.size() > PAGE_SIZE, payments);
    }

    @Transactional(readOnly = true)
    public Payment payment(AuthenticatedUser user, UUID attemptId) {
        requireContext(user, false);
        if (attemptId == null) throw missing();
        // Resolve only within the authenticated organisation/database actor.
        // Then validate the exact historical account's live own-child scope.
        var p = new MapSqlParameterSource().addValue("organisationId", user.activeOrganisationId())
                .addValue("actorUserId", user.userId()).addValue("attemptId", attemptId);
        var rows = jdbc.query(ATTEMPTS + " AND attempt.id=:attemptId", p, (row, n) -> map(row));
        if (rows.size() != 1) throw missing();
        var attempt = rows.getFirst();
        var account = access.requireAccount(user, attempt.learnerId(), attempt.accountId());
        return payment(user, account.scope(), attempt, databaseNow(parameters(user, account.scope())));
    }

    @Transactional
    public Payment initiate(AuthenticatedUser user, UUID learnerId, InitiateRequest request) {
        requireContext(user, true);
        var terms = terms(request);
        var scope = access.requireChild(user, learnerId);
        var p = parameters(user, scope).addValue("requestId", terms.clientRequestId());
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                p.addValue("lockKey", scope.schoolId() + ":finance"), (row, n) -> true);
        // A session/relationship or configuration revoked while waiting is not
        // replaced with SYSTEM authority and must not reserve any new funds.
        scope = access.requireChild(user, learnerId);
        p = parameters(user, scope).addValue("requestId", terms.clientRequestId());
        UUID existing = jdbc.query("""
                SELECT attempt.id FROM fee_gateway_attempts attempt JOIN learner_fee_accounts account
                  ON account.id=attempt.learner_fee_account_id AND account.organisation_id=attempt.organisation_id AND account.school_id=attempt.school_id
                WHERE attempt.organisation_id=:organisationId AND attempt.school_id=:schoolId
                  AND account.learner_id=:learnerId AND attempt.initiated_by_user_id=:actorUserId AND attempt.client_request_id=:requestId
                  AND current_app_organisation_id()=:organisationId AND current_app_user_id()=:actorUserId
                """, p, row -> row.next() ? row.getObject(1, UUID.class) : null);
        if (existing != null) {
            // Terms/phone validation still belongs to the existing reservation
            // service's explicit original-account replay, never today's latest
            // fee account. Never rebind/re-enqueue an acknowledged retry.
            var originals = jdbc.query(ATTEMPTS + " AND attempt.id=:attemptId AND attempt.school_id=:schoolId AND account.learner_id=:learnerId",
                    parameters(user, scope).addValue("attemptId", existing), (row, n) -> map(row));
            if (originals.size() != 1) throw missing();
            access.requireAccount(user, learnerId, originals.getFirst().accountId());
            var original = reservations.replay(user, learnerId, existing, terms);
            if (!existing.equals(original.id())) throw unavailable();
            return payment(user, original.id());
        }
        var admitted = route(scope);
        if (admitted.availability() != Availability.AVAILABLE || admitted.route() == null) throw unavailable();
        var prepared = reservations.prepare(user, learnerId, terms);
        var rows = jdbc.query(ATTEMPTS + " AND attempt.id=:attemptId AND attempt.school_id=:schoolId AND account.learner_id=:learnerId",
                parameters(user, scope).addValue("attemptId", prepared.id()), (row, n) -> map(row));
        if (rows.size() != 1) throw unavailable();
        var attempt = rows.getFirst();
        var account = access.requireAccount(user, learnerId, attempt.accountId());
        var current = route(account.scope());
        if (current.availability() != Availability.AVAILABLE || !admitted.route().equals(current.route())
                || !"ACTIVE".equals(account.status()) || !"INR".equals(account.currencyCode())
                || !user.userId().equals(attempt.initiatedBy()) || !matches(attempt, current.route())) throw unavailable();
        UUID queued = queue.enqueueAttempt(binding(current.route()), prepared.id());
        if (!prepared.id().equals(queued)) throw unavailable(); // Entire reservation/enqueue transaction rolls back.
        return payment(user, prepared.id());
    }

    @Transactional
    public Continuation continueCheckout(AuthenticatedUser user, UUID learnerId, UUID attemptId) {
        requireContext(user, true);
        var continued = checkout.continueCheckout(user, learnerId, attemptId);
        return new Continuation(continued.attemptId(), continued.checkoutUrl(), continued.expiresAt());
    }

    private Payment payment(AuthenticatedUser user, FamilyGatewayAccess.Scope scope, Attempt attempt, Instant now) {
        if (!scope.organisationId().equals(attempt.organisationId()) || !scope.schoolId().equals(attempt.schoolId())
                || !scope.learnerId().equals(attempt.learnerId())) throw missing();
        var p = parameters(user, scope).addValue("attemptId", attempt.id()).addValue("accountId", attempt.accountId());
        var charges = jdbc.query("""
                SELECT allocation.learner_fee_charge_id,charge.label_snapshot,charge.due_on,allocation.amount
                FROM fee_gateway_allocations allocation JOIN learner_fee_charges charge ON charge.id=allocation.learner_fee_charge_id
                  AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
                  AND charge.learner_fee_account_id=allocation.learner_fee_account_id
                WHERE allocation.attempt_id=:attemptId AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId
                  AND allocation.learner_fee_account_id=:accountId ORDER BY charge.due_on,allocation.learner_fee_charge_id LIMIT 101
                """, p, (row, n) -> new SelectedCharge(row.getObject("learner_fee_charge_id", UUID.class), row.getString("label_snapshot"),
                        row.getObject("due_on", LocalDate.class), row.getBigDecimal("amount")));
        if (charges.isEmpty() || charges.size() > 100) throw unavailable();
        boolean canContinue = attempt.receipt() == null && !attempt.reviewEvidence()
                && List.of("RESERVED", "CHECKOUT_PENDING").contains(attempt.state()) && now.isBefore(attempt.expiresAt())
                && attempt.reservationActive() && user.userId().equals(attempt.initiatedBy())
                && checkout.canContinue(user, attempt.learnerId(), attempt.id());
        Status status = status(attempt, canContinue, now);
        return new Payment(attempt.id(), attempt.learnerId(), attempt.accountId(), attempt.sessionName(), attempt.amount(), attempt.currency(),
                status, attempt.createdAt(), attempt.expiresAt(), attempt.originalBusinessDate(), attempt.reservationActive(),
                user.userId().equals(attempt.initiatedBy()), canContinue && status == Status.READY, charges, attempt.receipt());
    }

    private static Status status(Attempt attempt, boolean canContinue, Instant now) {
        if (attempt.receipt() != null) return attempt.additionalReviewEvidence() ? Status.REVIEW_REQUIRED : Status.PAID;
        if (attempt.reviewEvidence() || "CAPTURED_REVIEW_REQUIRED".equals(attempt.state()) || "POSTED".equals(attempt.state())) return Status.REVIEW_REQUIRED;
        if ("RELEASED".equals(attempt.state())) return Status.RELEASED;
        if (!List.of("RESERVED", "CHECKOUT_PENDING").contains(attempt.state())) return Status.REVIEW_REQUIRED;
        if (!now.isBefore(attempt.expiresAt())) return Status.EXPIRED;
        return canContinue ? Status.READY : Status.PENDING;
    }

    private RouteAvailability route(FamilyGatewayAccess.Scope scope) {
        if (!registry.creationEnabled() || !registry.workerEnabled()) return new RouteAvailability(Availability.DISABLED, null);
        var rows = jdbc.query("""
                SELECT id,provider_code,environment,merchant_account_reference,credentials_secret_reference
                FROM payment_provider_configs WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                ORDER BY id LIMIT 2
                """, new MapSqlParameterSource().addValue("organisationId", scope.organisationId()).addValue("schoolId", scope.schoolId()),
                (row, n) -> new Provider(row.getObject("id", UUID.class), row.getString("provider_code"), row.getString("environment"),
                        row.getString("merchant_account_reference"), row.getString("credentials_secret_reference")));
        if (rows.size() != 1) return new RouteAvailability(Availability.UNAVAILABLE, null);
        var provider = rows.getFirst();
        var matches = registry.configuredRoutes().stream().filter(candidate -> candidate.organisationId().equals(scope.organisationId())
                && candidate.schoolId().equals(scope.schoolId()) && candidate.providerConfigId().equals(provider.id())
                && candidate.providerCode().equals(provider.code()) && candidate.environment().name().equals(provider.environment())
                && candidate.merchantAccountReference().equals(provider.merchant()) && candidate.credentialsSecretReference().equals(provider.credentials())).toList();
        if (matches.size() != 1) return new RouteAvailability(Availability.UNAVAILABLE, null);
        var configured = matches.getFirst();
        if (configured.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) {
            return new RouteAvailability(Availability.DISABLED, null);
        }
        return new RouteAvailability(Availability.AVAILABLE, configured);
    }

    private void requireContext(AuthenticatedUser user, boolean writable) {
        if (user == null || user.userId() == null || user.activeOrganisationId() == null || user.membershipIds().isEmpty()
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || writable && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TenantRequestContext.current().map(context -> user.userId().equals(context.userId())
                    && user.activeOrganisationId().equals(context.organisationId())).orElse(false)) throw missing();
        Boolean databaseMatches = jdbc.queryForObject("""
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id()=:actorUserId
                """, new MapSqlParameterSource().addValue("organisationId", user.activeOrganisationId()).addValue("actorUserId", user.userId()), Boolean.class);
        if (!Boolean.TRUE.equals(databaseMatches)) throw missing();
    }
    private static GatewayReservationService.PrepareRequest terms(InitiateRequest request) {
        try {
            if (request == null) throw new IllegalArgumentException();
            return new GatewayReservationService.PrepareRequest(request.clientRequestId(), request.allocations().stream()
                    .map(selection -> new GatewayReservationService.ChargeSelection(selection.chargeId(), selection.amount())).toList(), request.payerPhone(), request.accountId());
        } catch (RuntimeException invalid) { throw invalid("Choose valid unique fee charges, an eligible amount and a 10-digit payer phone."); }
    }
    private static boolean matches(Attempt attempt, GatewayProviderRegistry.Route route) {
        return route != null && route.organisationId().equals(attempt.organisationId()) && route.schoolId().equals(attempt.schoolId())
                && route.providerConfigId().equals(attempt.providerId()) && route.providerCode().equals(attempt.providerCode())
                && route.environment().name().equals(attempt.environment()) && route.merchantAccountReference().equals(attempt.merchant());
    }
    private static GatewayWorkQueue.ProviderBinding binding(GatewayProviderRegistry.Route route) {
        return new GatewayWorkQueue.ProviderBinding(route.organisationId(), route.schoolId(), route.providerConfigId(), route.providerCode(),
                route.environment().name(), route.merchantAccountReference(), route.credentialsSecretReference());
    }
    private static MapSqlParameterSource parameters(AuthenticatedUser user, FamilyGatewayAccess.Scope scope) {
        return new MapSqlParameterSource().addValue("organisationId", scope.organisationId()).addValue("schoolId", scope.schoolId())
                .addValue("learnerId", scope.learnerId()).addValue("actorUserId", user.userId());
    }
    private Instant databaseNow(MapSqlParameterSource p) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", p,
                (row, n) -> row.getObject(1, OffsetDateTime.class).toInstant()));
    }
    private static Attempt map(ResultSet row) throws SQLException {
        UUID receiptId = row.getObject("receipt_id", UUID.class);
        return new Attempt(row.getObject("id", UUID.class), row.getObject("organisation_id", UUID.class), row.getObject("school_id", UUID.class),
                row.getObject("learner_id", UUID.class), row.getObject("learner_fee_account_id", UUID.class), row.getString("session_name"),
                row.getBigDecimal("amount"), row.getString("currency_code"), row.getString("status"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getObject("original_business_date", LocalDate.class), row.getObject("initiated_by_user_id", UUID.class),
                row.getObject("payment_provider_config_id", UUID.class), row.getString("provider_code"), row.getString("provider_environment"),
                row.getString("merchant_account_reference"), row.getBoolean("reservation_active"), row.getBoolean("review_evidence"),
                row.getBoolean("additional_review_evidence"), receiptId == null ? null : new Receipt(receiptId, row.getString("receipt_number"),
                    row.getObject("receipt_posting_on", LocalDate.class)));
    }
    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND, "FAMILY_PAYMENT_NOT_FOUND", "This payment or learner is not available through your active family relationship.");
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT, "FAMILY_PAYMENT_UNAVAILABLE", "Online payment is currently unavailable. Refresh or contact the school; do not pay the same pending amount again.");
    }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "FAMILY_PAYMENT_INVALID", message); }
    private record RouteAvailability(Availability availability, GatewayProviderRegistry.Route route) {}
    private record Provider(UUID id, String code, String environment, String merchant, String credentials) {
        @Override public String toString() { return "Provider[private scoped gateway configuration]"; }
    }
    private record Attempt(UUID id, UUID organisationId, UUID schoolId, UUID learnerId, UUID accountId, String sessionName,
            BigDecimal amount, String currency, String state, Instant createdAt, Instant expiresAt, LocalDate originalBusinessDate,
            UUID initiatedBy, UUID providerId, String providerCode, String environment, String merchant, boolean reservationActive,
            boolean reviewEvidence, boolean additionalReviewEvidence, Receipt receipt) {
        @Override public String toString() { return "Attempt[private scoped family payment state]"; }
    }
}
