package com.educore.api.finance;

import com.educore.api.tenancy.TenantRequestContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Internal immutable callback routing and same-ID GET admission, not webhook
 * authentication, provider I/O or financial authority. Current admission fences
 * the actual inbox lease. Historical admission permits only original-bound fact
 * retention after a lease expires/completes; it never makes terminal work mutable.
 */
@Service
public class GatewayInboxDispatch {
    private static final Set<String> PAYMENT_EVENTS = Set.of("PAYMENT_SUCCESS_WEBHOOK", "PAYMENT_FAILED_WEBHOOK", "PAYMENT_USER_DROPPED_WEBHOOK");
    private static final Set<String> REFUND_EVENTS = Set.of("REFUND_STATUS_WEBHOOK", "AUTO_REFUND_STATUS_WEBHOOK");
    private static final Set<String> WORK_STATUSES = Set.of("READY", "LEASED", "RETRY", "COMPLETE", "REVIEW_REQUIRED");
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayProviderRegistry registry;
    private final GatewayAttemptDispatch dispatch;

    public GatewayInboxDispatch(NamedParameterJdbcTemplate jdbc, GatewayProviderRegistry registry, GatewayAttemptDispatch dispatch) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.registry = Objects.requireNonNull(registry);
        this.dispatch = Objects.requireNonNull(dispatch);
    }

    /**
     * Minimal private persisted routing only, never a browser DTO or a success
     * assertion. The flag is a current scoped eligibility signal only: neither
     * this record nor a callback alone authorizes capture or financial posting.
     * Historical snapshots always keep it false.
     */
    public record Admission(Optional<GatewayAttemptDispatch.ReadRecovery> attempt, String eventType,
            String orderReference, String paymentReference, String refundReference,
            boolean financiallyEnabled, Instant checkedAt) {
        public Admission {
            try {
                Objects.requireNonNull(attempt); Objects.requireNonNull(checkedAt);
                GatewayExactInstant.canonical(checkedAt);
                PaymentProviderAdapter.requireReference(paymentReference);
                if (orderReference != null) PaymentProviderAdapter.requireReference(orderReference);
                if (refundReference != null) PaymentProviderAdapter.requireReference(refundReference);
                boolean payment = PAYMENT_EVENTS.contains(eventType == null ? "" : eventType);
                boolean refund = REFUND_EVENTS.contains(eventType == null ? "" : eventType);
                if (!payment && !refund || payment && (orderReference == null || refundReference != null)
                        || "REFUND_STATUS_WEBHOOK".equals(eventType) && (orderReference == null || refundReference == null)
                        || refund && (attempt.isPresent() || financiallyEnabled)
                        || financiallyEnabled && (attempt.isEmpty() || !attempt.orElseThrow().createPreviouslyDispatched())) throw unavailable();
            } catch (RuntimeException invalid) { throw unavailable(); }
        }
        @Override public String toString() { return "Admission[private immutable scoped callback GET evidence]"; }
    }

    @Transactional
    public Admission loadForReadRecovery(String routeKey, GatewayWorkQueue.Claim claim) {
        return load(routeKey, claim, true);
    }

    /** No current-token assertion: V069 deliberately stores no former lease history. */
    @Transactional
    public Admission loadHistorical(String routeKey, GatewayWorkQueue.Claim claim) {
        return load(routeKey, claim, false);
    }

    private Admission load(String routeKey, GatewayWorkQueue.Claim claim, boolean current) {
        try {
            var binding = trustedBinding(routeKey, claim);
            var p = requireBackgroundScope(binding).addValue("inboxId", claim.id())
                    .addValue("leaseToken", claim.token())
                    .addValue("leaseStartedAt", GatewayExactInstant.parameter(claim.startedAt()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("leaseUntil", GatewayExactInstant.parameter(claim.leaseUntil()), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                    .addValue("attemptNumber", claim.attemptNumber());
            // All admission/finalization paths take this fence before mutable
            // inbox/work/attempt locks. Read-only organisation/role tables are
            // never row-locked or granted UPDATE merely for admission.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                    p.addValue("lockKey", binding.schoolId() + ":finance"), (r, n) -> true);
            Inbox inbox = jdbc.query("""
                    SELECT inbox.id AS inbox_id,inbox.organisation_id,inbox.school_id,inbox.payment_provider_config_id,
                      inbox.provider_code,inbox.provider_environment,inbox.merchant_account_reference,inbox.credentials_secret_reference,
                      inbox.event_type,inbox.checkout_reference,inbox.order_reference,inbox.payment_reference,inbox.refund_reference,
                      inbox.received_at,inbox.status AS inbox_status,inbox.lease_token,inbox.lease_started_at,inbox.lease_until,inbox.attempt_count,
                      provider.status AS provider_status,school.status AS school_status,organisation.status AS organisation_status
                    FROM fee_gateway_webhook_inbox inbox
                    JOIN payment_provider_configs provider ON provider.id=inbox.payment_provider_config_id
                      AND provider.organisation_id=inbox.organisation_id AND provider.school_id=inbox.school_id
                      AND provider.provider_code=inbox.provider_code AND provider.environment=inbox.provider_environment
                      AND provider.merchant_account_reference=inbox.merchant_account_reference
                      AND provider.credentials_secret_reference=inbox.credentials_secret_reference
                    JOIN schools school ON school.id=inbox.school_id AND school.organisation_id=inbox.organisation_id
                    JOIN organisations organisation ON organisation.id=inbox.organisation_id
                    WHERE inbox.id=:inboxId AND inbox.organisation_id=:organisationId AND inbox.school_id=:schoolId
                      AND inbox.payment_provider_config_id=:providerId AND inbox.provider_code=:providerCode
                      AND inbox.provider_environment=:environment AND inbox.merchant_account_reference=:merchant
                      AND inbox.credentials_secret_reference=:credentialsReference
                    """ + (current ? """
                      AND inbox.status='LEASED' AND inbox.lease_token=:leaseToken AND inbox.lease_started_at=:leaseStartedAt
                      AND inbox.lease_until=:leaseUntil AND inbox.attempt_count=:attemptNumber
                    """ : "") + " FOR SHARE OF inbox,provider", p, r -> {
                        if (!r.next()) return null;
                        var value = new Inbox(r.getObject("inbox_id", UUID.class),
                                new GatewayWorkQueue.ProviderBinding(r.getObject("organisation_id", UUID.class), r.getObject("school_id", UUID.class),
                                        r.getObject("payment_provider_config_id", UUID.class), r.getString("provider_code"), r.getString("provider_environment"),
                                        r.getString("merchant_account_reference"), r.getString("credentials_secret_reference")),
                                r.getString("event_type"), r.getObject("checkout_reference", UUID.class), r.getString("order_reference"),
                                r.getString("payment_reference"), r.getString("refund_reference"), instant(r, "received_at"), r.getString("inbox_status"),
                                r.getObject("lease_token", UUID.class), instant(r, "lease_started_at"), instant(r, "lease_until"), r.getInt("attempt_count"),
                                "ACTIVE".equals(r.getString("provider_status")) && "ACTIVE".equals(r.getString("school_status"))
                                        && "ACTIVE".equals(r.getString("organisation_status")));
                        if (r.next()) throw unavailable();
                        return value;
                    });
            if (inbox == null || !claim.id().equals(inbox.id()) || !binding.equals(inbox.binding()) || inbox.receivedAt() == null
                    || !WORK_STATUSES.contains(inbox.status() == null ? "" : inbox.status())
                    || claim.startedAt().isBefore(inbox.receivedAt()) || claim.attemptNumber() > inbox.attemptCount()) throw unavailable();
            if (current && (!"LEASED".equals(inbox.status()) || !claim.token().equals(inbox.token())
                    || !claim.startedAt().equals(inbox.startedAt()) || !claim.leaseUntil().equals(inbox.leaseUntil())
                    || claim.attemptNumber() != inbox.attemptCount())) throw unavailable();
            boolean payment = PAYMENT_EVENTS.contains(inbox.eventType());
            if (!payment && !REFUND_EVENTS.contains(inbox.eventType()) || !payment && inbox.checkoutReference() != null) throw unavailable();
            // Constructor validates immutable event/reference shape even for
            // orphan/refund cases. Never trust mutable callback DTOs here.
            new Admission(Optional.empty(), inbox.eventType(), inbox.orderReference(), inbox.paymentReference(), inbox.refundReference(), false, inbox.receivedAt());
            Optional<GatewayAttemptDispatch.ReadRecovery> attempt = Optional.empty();
            if (payment) {
                UUID attemptId = inbox.checkoutReference();
                if (attemptId == null) attemptId = associatedAttempt(p.addValue("orderReference", inbox.orderReference()));
                if (attemptId != null) {
                    Instant readLeaseUntil = inbox.leaseUntil() == null ? claim.leaseUntil() : inbox.leaseUntil();
                    attempt = Optional.ofNullable(loadAttempt(p.addValue("attemptId", attemptId), binding, attemptId, readLeaseUntil));
                }
            }
            // The database clock must be sampled after every row/fence wait.
            Instant checkedAt = jdbc.queryForObject("SELECT clock_timestamp()", p,
                    (r, n) -> r.getObject(1, OffsetDateTime.class).toInstant());
            if (checkedAt == null || checkedAt.isBefore(inbox.receivedAt()) || checkedAt.isBefore(claim.startedAt())
                    || current && !checkedAt.isBefore(claim.leaseUntil())) throw unavailable();
            return new Admission(attempt, inbox.eventType(), inbox.orderReference(), inbox.paymentReference(), inbox.refundReference(),
                    current && payment && attempt.isPresent() && attempt.orElseThrow().createPreviouslyDispatched()
                            && inbox.financiallyEnabled(), checkedAt);
        } catch (RuntimeException failure) {
            // SQL/parser failures can carry private routing values. Never expose
            // their causes, provider references or supplied return addresses.
            throw unavailable();
        }
    }

    private UUID associatedAttempt(MapSqlParameterSource p) {
        List<UUID> matches = jdbc.query("""
                SELECT DISTINCT observation.attempt_id
                FROM fee_gateway_checkout_observations observation
                JOIN fee_gateway_attempts attempt ON attempt.id=observation.attempt_id
                  AND attempt.organisation_id=observation.organisation_id AND attempt.school_id=observation.school_id
                  AND attempt.payment_provider_config_id=observation.payment_provider_config_id
                  AND attempt.checkout_reference=observation.provider_checkout_reference AND attempt.id=observation.attempt_reference
                JOIN fee_gateway_attempt_work work ON work.attempt_id=attempt.id AND work.organisation_id=attempt.organisation_id
                  AND work.school_id=attempt.school_id AND work.payment_provider_config_id=attempt.payment_provider_config_id
                  AND work.provider_code=attempt.provider_code AND work.provider_environment=attempt.provider_environment
                  AND work.merchant_account_reference=attempt.merchant_account_reference
                WHERE observation.organisation_id=:organisationId AND observation.school_id=:schoolId
                  AND observation.payment_provider_config_id=:providerId AND :orderReference=ANY(observation.order_references)
                  AND work.organisation_id=:organisationId AND work.school_id=:schoolId AND work.payment_provider_config_id=:providerId
                  AND work.provider_code=:providerCode AND work.provider_environment=:environment
                  AND work.merchant_account_reference=:merchant AND work.credentials_secret_reference=:credentialsReference
                ORDER BY observation.attempt_id LIMIT 2
                """, p, (r, n) -> r.getObject(1, UUID.class));
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private GatewayAttemptDispatch.ReadRecovery loadAttempt(MapSqlParameterSource p, GatewayWorkQueue.ProviderBinding binding,
            UUID attemptId, Instant leaseUntil) {
        return jdbc.query("""
                SELECT attempt.id,attempt.learner_fee_account_id,attempt.checkout_reference,attempt.amount,attempt.currency_code,
                  attempt.status AS attempt_status,account.status AS account_status,attempt.school_time_zone,attempt.original_business_date,
                  attempt.created_at,attempt.expires_at,attempt.return_uri,attempt.row_version,
                  work.create_dispatched_at IS NOT NULL AS create_dispatched
                FROM fee_gateway_attempt_work work JOIN fee_gateway_attempts attempt ON attempt.id=work.attempt_id
                  AND attempt.organisation_id=work.organisation_id AND attempt.school_id=work.school_id
                  AND attempt.payment_provider_config_id=work.payment_provider_config_id AND attempt.provider_code=work.provider_code
                  AND attempt.provider_environment=work.provider_environment AND attempt.merchant_account_reference=work.merchant_account_reference
                JOIN payment_provider_configs provider ON provider.id=work.payment_provider_config_id
                  AND provider.organisation_id=work.organisation_id AND provider.school_id=work.school_id
                  AND provider.provider_code=work.provider_code AND provider.environment=work.provider_environment
                  AND provider.merchant_account_reference=work.merchant_account_reference
                  AND provider.credentials_secret_reference=work.credentials_secret_reference
                JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id
                  AND account.organisation_id=attempt.organisation_id AND account.school_id=attempt.school_id
                WHERE work.attempt_id=:attemptId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode AND work.provider_environment=:environment
                  AND work.merchant_account_reference=:merchant AND work.credentials_secret_reference=:credentialsReference
                FOR SHARE OF work,attempt,provider,account
                """, p, r -> {
                    if (!r.next()) return null;
                    var value = new GatewayAttemptDispatch.ReadRecovery(r.getObject("id", UUID.class), r.getObject("learner_fee_account_id", UUID.class),
                            r.getObject("checkout_reference", UUID.class), binding, r.getBigDecimal("amount"), r.getString("currency_code"),
                            r.getString("attempt_status"), r.getString("account_status"), r.getString("school_time_zone"),
                            r.getObject("original_business_date", LocalDate.class), instant(r, "created_at"), instant(r, "expires_at"),
                            dispatch.safeReturnUri(r.getString("return_uri"), attemptId), r.getLong("row_version"), leaseUntil, r.getBoolean("create_dispatched"));
                    if (!attemptId.equals(value.attemptId()) || r.next()) throw unavailable();
                    return value;
                });
    }

    private GatewayWorkQueue.ProviderBinding trustedBinding(String routeKey, GatewayWorkQueue.Claim claim) {
        if (!registry.workerEnabled() || claim == null || claim.kind() != GatewayWorkQueue.WorkKind.WEBHOOK) throw unavailable();
        var route = registry.findRoute(routeKey).orElseThrow(GatewayInboxDispatch::unavailable);
        if (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) throw unavailable();
        var binding = new GatewayWorkQueue.ProviderBinding(route.organisationId(), route.schoolId(), route.providerConfigId(),
                route.providerCode(), route.environment().name(), route.merchantAccountReference(), route.credentialsSecretReference());
        if (!binding.equals(claim.binding())) throw unavailable();
        return binding;
    }

    private MapSqlParameterSource requireBackgroundScope(GatewayWorkQueue.ProviderBinding binding) {
        var context = TenantRequestContext.current().orElseThrow(GatewayInboxDispatch::unavailable);
        if (context.userId() != null || !binding.organisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
        var p = new MapSqlParameterSource().addValue("organisationId", binding.organisationId()).addValue("schoolId", binding.schoolId())
                .addValue("providerId", binding.providerConfigId()).addValue("providerCode", binding.providerCode())
                .addValue("environment", binding.environment()).addValue("merchant", binding.merchantReference())
                .addValue("credentialsReference", binding.credentialsReference());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL", p, Boolean.class))) throw unavailable();
        return p;
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
    private static IllegalStateException unavailable() {
        return new IllegalStateException("Gateway inbox GET requires its trusted persisted routing, exact background scope and eligible current or historical claim");
    }
    private record Inbox(UUID id, GatewayWorkQueue.ProviderBinding binding, String eventType, UUID checkoutReference,
            String orderReference, String paymentReference, String refundReference, Instant receivedAt, String status,
            UUID token, Instant startedAt, Instant leaseUntil, int attemptCount, boolean financiallyEnabled) {
        @Override public String toString() { return "Inbox[private original authenticated callback routing]"; }
    }
}
