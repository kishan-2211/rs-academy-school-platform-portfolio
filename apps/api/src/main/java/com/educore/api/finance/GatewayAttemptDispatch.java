package com.educore.api.finance;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Separate transactional admission for same-ID GET recovery and a NEW one-use
 * CREATE. Neither boundary resolves credentials, decrypts the payer phone,
 * performs provider I/O, alters attempts or issues receipts. GET snapshots never
 * imply CREATE permission. NEW CREATE additionally requires current live family
 * and payable fees, independently approved accounting readiness and its committed
 * one-use marker; an unknown dispatch can only recover through same-ID GET.
 *
 * The future orchestrator must call provider GET after this transaction commits,
 * and independently revalidate retained evidence before any financial write.
 */
@Service
public class GatewayAttemptDispatch {
    private static final Set<String> ATTEMPT_STATUSES = Set.of(
            "RESERVED", "CHECKOUT_PENDING", "CAPTURED_REVIEW_REQUIRED", "POSTED", "RELEASED");
    private final NamedParameterJdbcTemplate jdbc;
    private final GatewayProviderRegistry registry;
    private final ApplicationProperties properties;
    private final GatewayCheckoutReadiness readiness;

    public GatewayAttemptDispatch(NamedParameterJdbcTemplate jdbc, GatewayProviderRegistry registry,
            ApplicationProperties properties, GatewayCheckoutReadiness readiness) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.registry = Objects.requireNonNull(registry);
        this.properties = Objects.requireNonNull(properties);
        this.readiness = Objects.requireNonNull(readiness);
    }

    /** Internal admission evidence; no credentials/phone, HTTP call or financial posting. */
    public record CreateAdmission(ReadRecovery attempt, GatewayCheckoutReadiness.ReadyBook book, Instant dispatchedAt) {
        @Override public String toString() { return "CreateAdmission[internal one-use scoped gateway terms]"; }
    }

    /**
     * Admit a NEW same-ID CREATE only after the current parent grant, immutable
     * reservation and independently approved accounting configuration are still
     * usable. The one-use marker is written last in this transaction; a caller
     * must await its commit before any provider I/O. Any unknown outcome can
     * subsequently use GET only, never retry CREATE.
     */
    @Transactional
    public CreateAdmission admitCreate(String routeKey, GatewayWorkQueue.Claim claim) {
        if (!registry.creationEnabled() || !registry.workerEnabled() || claim == null
                || claim.kind() != GatewayWorkQueue.WorkKind.ATTEMPT || claim.createDispatched()) throw createUnavailable();
        var trustedRoute = registry.findRoute(routeKey).orElseThrow(GatewayAttemptDispatch::createUnavailable);
        if (trustedRoute.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) throw createUnavailable();
        var trustedBinding = new GatewayWorkQueue.ProviderBinding(trustedRoute.organisationId(), trustedRoute.schoolId(),
                trustedRoute.providerConfigId(), trustedRoute.providerCode(), trustedRoute.environment().name(),
                trustedRoute.merchantAccountReference(), trustedRoute.credentialsSecretReference());
        if (!trustedBinding.equals(claim.binding())) throw createUnavailable();
        var parameters = requireBackgroundScope(trustedBinding);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                parameters.addValue("lockKey", claim.binding().schoolId() + ":finance"), (r, n) -> true);
        ReadRecovery snapshot = loadForReadRecovery(routeKey, claim);
        if (snapshot.createPreviouslyDispatched() || !"RESERVED".equals(snapshot.attemptStatus())
                || !"ACTIVE".equals(snapshot.accountStatus())) throw createUnavailable();
        parameters.addValue("attemptId", claim.id()).addValue("accountId", snapshot.accountId());
        // Lock the live initiating human grant without inventing an AuthenticatedUser
        // in the SYSTEM worker. Revocation after dispatch cannot erase provider facts.
        var grants = jdbc.query("""
                SELECT member.id FROM fee_gateway_attempts attempt
                JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id
                  AND account.organisation_id=attempt.organisation_id AND account.school_id=attempt.school_id
                JOIN learners learner ON learner.id=account.learner_id AND learner.organisation_id=account.organisation_id
                  AND learner.school_id=account.school_id AND learner.status='ACTIVE'
                JOIN schools school ON school.id=attempt.school_id AND school.organisation_id=attempt.organisation_id
                  AND school.status='ACTIVE' AND school.time_zone=attempt.school_time_zone
                JOIN organisations organisation ON organisation.id=attempt.organisation_id AND organisation.status='ACTIVE'
                JOIN memberships member ON member.id=attempt.initiated_by_membership_id
                  AND member.user_id=attempt.initiated_by_user_id AND member.organisation_id=attempt.organisation_id
                  AND member.school_id=attempt.school_id AND member.status='ACTIVE'
                  AND member.valid_from<=clock_timestamp() AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
                JOIN user_accounts issuer ON issuer.id=member.user_id AND issuer.status='ACTIVE'
                JOIN guardians guardian ON guardian.user_account_id=issuer.id AND guardian.organisation_id=attempt.organisation_id
                  AND guardian.school_id=attempt.school_id AND guardian.status='ACTIVE'
                JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
                  AND relationship.learner_id=learner.id AND relationship.organisation_id=guardian.organisation_id
                  AND relationship.school_id=guardian.school_id AND relationship.portal_access_status='ACTIVE'
                WHERE attempt.id=:attemptId AND attempt.organisation_id=:organisationId AND attempt.school_id=:schoolId
                  AND account.status='ACTIVE' AND account.currency_code='INR'
                  AND (attempt.account_selection_mode='EXPLICIT_ACCOUNT'
                    OR (attempt.account_selection_mode='CURRENT_SESSION' AND account.academic_session_id=(SELECT enrolment.academic_session_id FROM student_enrolments enrolment
                    WHERE enrolment.learner_id=learner.id AND enrolment.organisation_id=learner.organisation_id
                      AND enrolment.school_id=learner.school_id AND enrolment.status='ACTIVE'
                    ORDER BY enrolment.starts_on DESC,enrolment.id DESC LIMIT 1)))
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='portal.guardian'
                    WHERE mr.membership_id=member.id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id))
                FOR SHARE OF account,learner,school,member,issuer,guardian,relationship
                """, parameters, (r, n) -> r.getObject("id", UUID.class));
        if (grants.size() != 1) throw createUnavailable();
        parameters.addValue("memberId", grants.getFirst());
        var capabilities = jdbc.query("""
                SELECT mr.role_id FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                  JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='portal.guardian'
                WHERE mr.membership_id=:memberId AND (role.organisation_id IS NULL OR role.organisation_id=:organisationId)
                FOR SHARE OF mr
                """, parameters, (r, n) -> r.getObject("role_id", UUID.class));
        if (capabilities.isEmpty()) throw createUnavailable();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fee_receipt_sequences sequence JOIN learner_fee_accounts account
                    ON account.academic_session_id=sequence.academic_session_id
                      AND account.organisation_id=sequence.organisation_id AND account.school_id=sequence.school_id
                    WHERE account.id=:accountId AND account.organisation_id=:organisationId AND account.school_id=:schoolId)
                  AND NOT EXISTS(SELECT 1 FROM fee_day_closes close WHERE close.organisation_id=:organisationId
                    AND close.school_id=:schoolId AND close.status='APPROVED' AND close.business_date>=:businessDate)
                  AND :businessDate=(clock_timestamp() AT TIME ZONE :timeZone)::date
                  AND (SELECT COALESCE(sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END),0)
                    FROM fee_ledger_entries WHERE learner_fee_account_id=:accountId
                      AND organisation_id=:organisationId AND school_id=:schoolId)
                    >=(SELECT COALESCE(sum(amount),0) FROM fee_gateway_allocations WHERE learner_fee_account_id=:accountId
                      AND organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE')
                """, parameters.addValue("businessDate", snapshot.originalBusinessDate())
                        .addValue("timeZone", snapshot.schoolTimeZone()), Boolean.class))) throw createUnavailable();
        var allocations = jdbc.query("""
                SELECT allocation.amount,charge.amount AS charge_amount,charge.allow_partial,
                  COALESCE((SELECT sum(entry.amount) FROM fee_ledger_entries entry WHERE entry.learner_fee_charge_id=charge.id
                    AND entry.organisation_id=charge.organisation_id AND entry.school_id=charge.school_id AND entry.entry_type='CONCESSION'),0) concessions,
                  fee_charge_net_paid(charge.organisation_id,charge.school_id,charge.id,NULL::date) paid,
                  COALESCE((SELECT sum(reserved.amount) FROM fee_gateway_allocations reserved WHERE reserved.learner_fee_charge_id=charge.id
                    AND reserved.organisation_id=charge.organisation_id AND reserved.school_id=charge.school_id AND reserved.status='ACTIVE'),0) reserved
                FROM fee_gateway_allocations allocation JOIN learner_fee_charges charge
                  ON charge.id=allocation.learner_fee_charge_id AND charge.learner_fee_account_id=allocation.learner_fee_account_id
                  AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
                WHERE allocation.attempt_id=:attemptId AND allocation.learner_fee_account_id=:accountId
                  AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId AND allocation.status='ACTIVE'
                FOR SHARE OF allocation,charge
                """, parameters, (r, n) -> new CreateAllocation(r.getBigDecimal("amount"), r.getBigDecimal("charge_amount"),
                r.getBigDecimal("concessions"), r.getBigDecimal("paid"), r.getBigDecimal("reserved"), r.getBoolean("allow_partial")));
        if (allocations.isEmpty() || allocations.size() > 100
                || allocations.stream().map(CreateAllocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(snapshot.amount()) != 0
                || allocations.stream().anyMatch(allocation -> !allocation.eligible())) throw createUnavailable();
        var book = readiness.validate(claim.binding().organisationId(), claim.binding().schoolId(), claim.id(),
                snapshot.accountId(), snapshot.originalBusinessDate());
        // Accounting/config row locks may have waited past the lease, TTL or grant
        // validity. Recheck actual DB time after ALL locks before consuming CREATE.
        Timestamp observed = jdbc.queryForObject("SELECT clock_timestamp()", parameters, Timestamp.class);
        if (observed == null || observed.toInstant().isBefore(claim.startedAt())
                || !observed.toInstant().isBefore(claim.leaseUntil()) || !observed.toInstant().isBefore(snapshot.expiresAt())
                || !snapshot.originalBusinessDate().equals(observed.toInstant().atZone(ZoneId.of(snapshot.schoolTimeZone())).toLocalDate())) {
            throw createUnavailable();
        }
        parameters.addValue("observedAt", observed).addValue("leaseToken", claim.token())
                .addValue("leaseStartedAt", Timestamp.from(claim.startedAt())).addValue("leaseUntil", Timestamp.from(claim.leaseUntil()))
                .addValue("attemptNumber", claim.attemptNumber());
        int changed = jdbc.update("""
                UPDATE fee_gateway_attempt_work work SET create_dispatched_at=:observedAt,
                  create_dispatched_by_lease_token=:leaseToken,updated_at=:observedAt,row_version=row_version+1
                WHERE work.attempt_id=:attemptId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.status='LEASED' AND work.lease_token=:leaseToken AND work.lease_started_at=:leaseStartedAt
                  AND work.lease_until=:leaseUntil AND work.attempt_count=:attemptNumber AND work.create_dispatched_at IS NULL
                  AND EXISTS(SELECT 1 FROM fee_gateway_attempts attempt JOIN memberships member
                    ON member.id=attempt.initiated_by_membership_id AND member.user_id=attempt.initiated_by_user_id
                    AND member.organisation_id=attempt.organisation_id AND member.school_id=attempt.school_id
                    JOIN organisations organisation ON organisation.id=attempt.organisation_id AND organisation.status='ACTIVE'
                    WHERE attempt.id=work.attempt_id AND attempt.status='RESERVED' AND attempt.expires_at>:observedAt
                      AND member.status='ACTIVE' AND member.valid_from<=:observedAt
                      AND (member.valid_until IS NULL OR member.valid_until>:observedAt)
                      AND EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                        JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='portal.guardian'
                        WHERE mr.membership_id=member.id AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)))
                """, parameters);
        if (changed != 1) throw createUnavailable();
        return new CreateAdmission(snapshot, book, observed.toInstant());
    }

    private record CreateAllocation(BigDecimal amount, BigDecimal charge, BigDecimal concessions,
            BigDecimal paid, BigDecimal reserved, boolean partial) {
        boolean eligible() {
            if (amount == null || charge == null || concessions == null || paid == null || reserved == null
                    || amount.signum() <= 0) return false;
            BigDecimal outstanding = charge.subtract(concessions).subtract(paid);
            return reserved.compareTo(outstanding) <= 0 && amount.compareTo(outstanding) <= 0
                    && (partial || amount.compareTo(outstanding) == 0);
        }
    }

    private static IllegalStateException createUnavailable() {
        return new IllegalStateException("Gateway CREATE requires current trusted one-use lease, live family, payable fees and approved accounting readiness");
    }

    /** Internal server evidence, never a browser DTO or an authority to create/post. */
    public record ReadRecovery(UUID attemptId, UUID accountId, UUID checkoutReference,
            GatewayWorkQueue.ProviderBinding binding, BigDecimal amount, String currencyCode,
            String attemptStatus, String accountStatus, String schoolTimeZone,
            LocalDate originalBusinessDate, Instant createdAt, Instant expiresAt, URI returnUri,
            long attemptVersion, Instant leaseUntil, boolean createPreviouslyDispatched) {
        public ReadRecovery {
            Objects.requireNonNull(attemptId); Objects.requireNonNull(accountId);
            Objects.requireNonNull(checkoutReference); Objects.requireNonNull(binding);
            Objects.requireNonNull(originalBusinessDate); Objects.requireNonNull(createdAt);
            Objects.requireNonNull(expiresAt); Objects.requireNonNull(returnUri); Objects.requireNonNull(leaseUntil);
            PaymentProviderAdapter.requireAmount(amount);
            if (!attemptId.equals(checkoutReference) || !"INR".equals(currencyCode)
                    || !ATTEMPT_STATUSES.contains(attemptStatus == null ? "" : attemptStatus)
                    || !Set.of("ACTIVE", "CLOSED").contains(accountStatus == null ? "" : accountStatus)
                    || attemptVersion < 0 || !expiresAt.isAfter(createdAt)
                    || expiresAt.isAfter(createdAt.plusSeconds(86400))) {
                throw unavailable();
            }
            try {
                if (!originalBusinessDate.equals(createdAt.atZone(ZoneId.of(schoolTimeZone)).toLocalDate())) {
                    throw unavailable();
                }
            } catch (RuntimeException failure) {
                throw unavailable();
            }
        }

        @Override public String toString() { return "ReadRecovery[internal scoped same-ID GET evidence]"; }
    }

    /**
     * Lock/recheck the current lease and frozen identity in a short writable
     * background transaction. Expired attempts or closed accounts can still be
     * read at the provider: neither is evidence that money was never received.
     */
    @Transactional
    public ReadRecovery loadForReadRecovery(String routeKey, GatewayWorkQueue.Claim claim) {
        if (!registry.workerEnabled() || claim == null || claim.kind() != GatewayWorkQueue.WorkKind.ATTEMPT) {
            throw unavailable();
        }
        var route = registry.findRoute(routeKey).orElseThrow(GatewayAttemptDispatch::unavailable);
        if (route.environment() == CashfreePaymentProviderAdapter.Environment.LIVE && !registry.liveEnabled()) {
            throw unavailable();
        }
        var binding = new GatewayWorkQueue.ProviderBinding(route.organisationId(), route.schoolId(),
                route.providerConfigId(), route.providerCode(), route.environment().name(),
                route.merchantAccountReference(), route.credentialsSecretReference());
        if (!binding.equals(claim.binding())) throw unavailable();
        var parameters = requireBackgroundScope(binding)
                .addValue("attemptId", claim.id()).addValue("leaseToken", claim.token())
                .addValue("leaseStartedAt", Timestamp.from(claim.startedAt()))
                .addValue("leaseUntil", Timestamp.from(claim.leaseUntil()))
                .addValue("attemptNumber", claim.attemptNumber());
        ReadRecovery snapshot = jdbc.query("""
                SELECT attempt.id,attempt.learner_fee_account_id,attempt.checkout_reference,
                  attempt.amount,attempt.currency_code,attempt.status AS attempt_status,
                  account.status AS account_status,attempt.school_time_zone,attempt.original_business_date,
                  attempt.created_at,attempt.expires_at,attempt.return_uri,attempt.row_version,
                  work.lease_until,work.create_dispatched_at IS NOT NULL AS create_dispatched
                FROM fee_gateway_attempt_work work
                JOIN fee_gateway_attempts attempt ON attempt.id=work.attempt_id
                  AND attempt.organisation_id=work.organisation_id AND attempt.school_id=work.school_id
                  AND attempt.payment_provider_config_id=work.payment_provider_config_id
                  AND attempt.provider_code=work.provider_code AND attempt.provider_environment=work.provider_environment
                  AND attempt.merchant_account_reference=work.merchant_account_reference
                JOIN payment_provider_configs provider ON provider.id=work.payment_provider_config_id
                  AND provider.organisation_id=work.organisation_id AND provider.school_id=work.school_id
                  AND provider.provider_code=work.provider_code AND provider.environment=work.provider_environment
                  AND provider.merchant_account_reference=work.merchant_account_reference
                  AND provider.credentials_secret_reference=work.credentials_secret_reference AND provider.status='ACTIVE'
                JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id
                  AND account.organisation_id=attempt.organisation_id AND account.school_id=attempt.school_id
                JOIN schools school ON school.id=attempt.school_id AND school.organisation_id=attempt.organisation_id
                  AND school.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=attempt.organisation_id AND organisation.status='ACTIVE'
                WHERE work.attempt_id=:attemptId AND work.organisation_id=:organisationId AND work.school_id=:schoolId
                  AND work.payment_provider_config_id=:providerId AND work.provider_code=:providerCode
                  AND work.provider_environment=:environment AND work.merchant_account_reference=:merchant
                  AND work.credentials_secret_reference=:credentialsReference AND work.status='LEASED'
                  AND work.lease_token=:leaseToken AND work.lease_started_at=:leaseStartedAt
                  AND work.lease_until=:leaseUntil AND work.attempt_count=:attemptNumber
                FOR SHARE OF work,attempt,provider
                """, parameters, row -> row.next() ? new ReadRecovery(
                        row.getObject("id", UUID.class), row.getObject("learner_fee_account_id", UUID.class),
                        row.getObject("checkout_reference", UUID.class), binding, row.getBigDecimal("amount"),
                        row.getString("currency_code"), row.getString("attempt_status"), row.getString("account_status"),
                        row.getString("school_time_zone"), row.getObject("original_business_date", LocalDate.class),
                        row.getTimestamp("created_at").toInstant(), row.getTimestamp("expires_at").toInstant(),
                        safeReturnUri(row.getString("return_uri"), claim.id()), row.getLong("row_version"),
                        row.getTimestamp("lease_until").toInstant(), row.getBoolean("create_dispatched")) : null);
        if (snapshot == null) throw unavailable();
        // A row-lock wait can outlast the lease even without a row update. Use
        // actual database time AFTER acquiring locks; the application clock or
        // the original claim time cannot keep a stale lease usable.
        Timestamp observedAt = jdbc.queryForObject("SELECT clock_timestamp()", parameters, Timestamp.class);
        if (observedAt == null || observedAt.toInstant().isBefore(claim.startedAt())
                || !observedAt.toInstant().isBefore(claim.leaseUntil())) {
            throw unavailable();
        }
        return snapshot;
    }

    private MapSqlParameterSource requireBackgroundScope(GatewayWorkQueue.ProviderBinding binding) {
        var context = TenantRequestContext.current().orElseThrow(GatewayAttemptDispatch::unavailable);
        if (context.userId() != null || !binding.organisationId().equals(context.organisationId())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw unavailable();
        }
        var parameters = new MapSqlParameterSource().addValue("organisationId", binding.organisationId())
                .addValue("schoolId", binding.schoolId()).addValue("providerId", binding.providerConfigId())
                .addValue("providerCode", binding.providerCode()).addValue("environment", binding.environment())
                .addValue("merchant", binding.merchantReference()).addValue("credentialsReference", binding.credentialsReference());
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT current_app_organisation_id()=:organisationId AND current_app_user_id() IS NULL
                """, parameters, Boolean.class))) throw unavailable();
        return parameters;
    }

    // Shared internal origin check for same-ID attempt and inbox GET admission.
    // This does not grant a callback or browser permission to follow the URI.
    URI safeReturnUri(String stored, UUID attemptId) {
        try {
            URI origin = URI.create(properties.publicUrl());
            if (origin.getHost() == null || !Set.of("http", "https").contains(origin.getScheme() == null ? "" : origin.getScheme())
                    || origin.getPort() == 0 || origin.getPort() < -1 || origin.getPort() > 65535
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))) {
                throw unavailable();
            }
            URI expected = URI.create(origin.getScheme() + "://" + origin.getRawAuthority() + "/family/payments/" + attemptId);
            if (!expected.toASCIIString().equals(stored)) throw unavailable();
            return expected;
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Gateway read recovery requires its current trusted background lease and immutable scope");
    }
}
