package com.educore.api.finance;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SensitiveValueCipher;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Test-only pre-V078 intent, never financial posting or a production compatibility path.
 * Real non-owner RLS, finance fences, immutable intent and deferred allocation guards
 * stay enabled. A separate bounded prior-schema receipt fixture supplies populated
 * upgrade history; current scenarios use actual domain services. An explicit V077
 * entry point supports its additive CURRENT_SESSION column only. Refuses V078 and
 * anything except the generated isolated database and fake school/provider data.
 */
final class LegacyGatewayIntentFixture {
    private LegacyGatewayIntentFixture() {}

    static GatewayReservationService.PlannedAttempt seed(JdbcTemplate jdbc, ApplicationProperties properties,
            Clock clock, AuthenticatedUser actor, UUID learnerId, UUID accountId, UUID providerId,
            GatewayReservationService.PrepareRequest request) {
        return seed(jdbc, properties, clock, actor, learnerId, accountId, providerId, request, false);
    }

    static GatewayReservationService.PlannedAttempt seedV077(JdbcTemplate jdbc, ApplicationProperties properties,
            Clock clock, AuthenticatedUser actor, UUID learnerId, UUID accountId, UUID providerId,
            GatewayReservationService.PrepareRequest request) {
        return seed(jdbc, properties, clock, actor, learnerId, accountId, providerId, request, true);
    }

    private static GatewayReservationService.PlannedAttempt seed(JdbcTemplate jdbc, ApplicationProperties properties,
            Clock clock, AuthenticatedUser actor, UUID learnerId, UUID accountId, UUID providerId,
            GatewayReservationService.PrepareRequest request, boolean v077) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !"https://synthetic-school.example.test".equals(properties.publicUrl())
                || request.accountId() != null
                || !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT current_database() ~ '^educore_(online|gateway_work)_[a-f0-9]{32}$'
                      AND current_app_user_id()=? AND current_app_organisation_id()=?
                      AND EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema='public' AND table_name='fee_gateway_attempts' AND column_name='account_selection_mode')=?
                      AND to_regprocedure('public.fee_charge_net_paid(uuid,uuid,uuid,date)') IS NULL
                      AND to_regclass('public.fee_gateway_refund_postings') IS NULL
                      AND NOT (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname=current_user)
                      AND current_user<>(SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database())
                      AND current_setting('row_security')='on'
                    """, Boolean.class, actor.userId(), actor.activeOrganisationId(), v077))) {
            throw new IllegalStateException("Isolated synthetic exact prior-schema family transaction required");
        }
        var context = jdbc.queryForMap("""
                SELECT account.school_id,school.time_zone,provider.merchant_account_reference,member.id AS membership_id
                FROM learner_fee_accounts account JOIN schools school ON school.id=account.school_id
                  AND school.organisation_id=account.organisation_id AND school.legal_name LIKE 'Synthetic%'
                JOIN learners learner ON learner.id=account.learner_id AND learner.legal_name='Synthetic learner'
                JOIN payment_provider_configs provider ON provider.id=? AND provider.school_id=account.school_id
                  AND provider.organisation_id=account.organisation_id AND provider.provider_code='CASHFREE'
                  AND provider.environment='TEST' AND provider.credentials_secret_reference='synthetic:not-enabled'
                JOIN memberships member ON member.user_id=? AND member.organisation_id=account.organisation_id
                  AND member.school_id=account.school_id AND member.status='ACTIVE'
                JOIN user_accounts actor ON actor.id=member.user_id AND actor.login_identifier_normalized LIKE 'synthetic-%@example.test'
                WHERE account.id=? AND account.learner_id=? AND account.organisation_id=?
                """, providerId, actor.userId(), accountId, learnerId, actor.activeOrganisationId());
        UUID schoolId = (UUID) context.get("school_id"), membershipId = (UUID) context.get("membership_id");
        if (!actor.membershipIds().contains(membershipId)) throw new IllegalStateException("Exact synthetic family membership required");
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", (r, n) -> true, schoolId + ":finance");
        UUID id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant expires = GatewayReservationService.newCheckoutExpiresAt(now);
        String timeZone = (String) context.get("time_zone");
        LocalDate day = LocalDate.ofInstant(now, ZoneId.of(timeZone));
        BigDecimal amount = request.allocations().stream().map(GatewayReservationService.ChargeSelection::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        String canonical = accountId + "|INR|" + String.join(",", request.allocations().stream()
                .map(a -> a.chargeId() + ":" + a.amount().setScale(2).toPlainString()).toList());
        String fingerprint;
        try { fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable"); }
        var encrypted = new SensitiveValueCipher(properties, new SecureRandom()).encrypt(request.payerPhone().getBytes(StandardCharsets.UTF_8),
                GatewayReservationService.associatedData(actor.activeOrganisationId(), schoolId, accountId, id));
        String columns = v077 ? ",account_selection_mode" : "";
        String values = v077 ? ",'CURRENT_SESSION'" : "";
        jdbc.update("""
                INSERT INTO fee_gateway_attempts(id,organisation_id,school_id,learner_fee_account_id,payment_provider_config_id,
                  initiated_by_user_id,initiated_by_membership_id,client_request_id,request_fingerprint,checkout_reference,
                  amount,currency_code,payer_phone_ciphertext,payer_phone_nonce,return_uri,provider_code,provider_environment,
                  merchant_account_reference,school_time_zone,expires_at,original_business_date,status,created_at,updated_at,row_version%s)
                VALUES(?,?,?,?,?,?,?,?,?, ?,?,'INR',?,?,?,'CASHFREE','TEST',?,?,?,?,'RESERVED',?,?,0%s)
                """.formatted(columns, values), id, actor.activeOrganisationId(), schoolId, accountId, providerId, actor.userId(), membershipId,
                request.clientRequestId(), fingerprint, id, amount, encrypted.ciphertext(), encrypted.nonce(),
                properties.publicUrl() + "/family/payments/" + id, context.get("merchant_account_reference"), timeZone,
                GatewayExactInstant.parameter(expires), day, GatewayExactInstant.parameter(now), GatewayExactInstant.parameter(now));
        for (var allocation : request.allocations()) jdbc.update("""
                INSERT INTO fee_gateway_allocations(id,attempt_id,learner_fee_account_id,learner_fee_charge_id,
                  organisation_id,school_id,amount,status,created_at,updated_at,row_version)
                VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?,0)
                """, UUID.randomUUID(), id, accountId, allocation.chargeId(), actor.activeOrganisationId(), schoolId,
                allocation.amount(), GatewayExactInstant.parameter(now), GatewayExactInstant.parameter(now));
        return new GatewayReservationService.PlannedAttempt(id, "RESERVED", amount, "INR", day, expires, 0);
    }
}
