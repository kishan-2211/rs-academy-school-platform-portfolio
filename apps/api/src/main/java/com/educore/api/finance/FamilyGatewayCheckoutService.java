package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SensitiveValueCipher;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit private URL disclosure, not CREATE, confirmation, receipt or reservation release. */
@Service
public class FamilyGatewayCheckoutService {
    private final NamedParameterJdbcTemplate jdbc;
    private final FamilyGatewayAccess access;
    private final GatewayProviderRegistry registry;
    private final SensitiveValueCipher cipher;
    private final SecurityAuditService audit;

    public FamilyGatewayCheckoutService(NamedParameterJdbcTemplate jdbc, FamilyGatewayAccess access,
            GatewayProviderRegistry registry, SensitiveValueCipher cipher, SecurityAuditService audit) {
        this.jdbc=Objects.requireNonNull(jdbc); this.access=Objects.requireNonNull(access);
        this.registry=Objects.requireNonNull(registry); this.cipher=Objects.requireNonNull(cipher); this.audit=Objects.requireNonNull(audit);
    }

    public record Continuation(UUID attemptId, URI checkoutUrl, Instant expiresAt) {
        @Override public String toString() { return "Continuation[private hosted checkout]"; }
    }

    /** Advisory only: no URL/decryption/audit/locks. The explicit POST rechecks every condition. */
    @Transactional(readOnly=true)
    public boolean canContinue(AuthenticatedUser user, UUID learnerId, UUID attemptId) {
        var child = access.requireChild(user, learnerId);
        if (attemptId == null) throw FamilyGatewayAccess.missing();
        try { return eligible(user, child, attemptId) != null; }
        catch (RuntimeException unavailable) { return false; } // Never expose private SQL/crypto diagnostics in status.
    }

    @Transactional
    public Continuation continueCheckout(AuthenticatedUser user, UUID learnerId, UUID attemptId) {
        var child = access.requireChild(user, learnerId);
        if (attemptId == null) throw FamilyGatewayAccess.missing();
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw unavailable();
        var p = parameters(user, child, attemptId);
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                p.addValue("lockKey", child.schoolId()+":finance"), (r,n)->true);
        // Recheck live relationship/session membership after the real fence wait.
        child = access.requireChild(user, learnerId);
        byte[] plain = null;
        try {
            var snapshot = eligible(user, child, attemptId);
            if (snapshot == null) throw unavailable();
            var account = access.requireAccount(user, learnerId, snapshot.accountId());
            if (!"ACTIVE".equals(account.status()) || !snapshot.accountId().equals(account.accountId())) throw unavailable();
            var rows = jdbc.query("""
                    SELECT url_ciphertext,url_nonce,url_sha256 FROM fee_gateway_hosted_checkouts
                    WHERE attempt_id=:attemptId AND learner_fee_account_id=:accountId
                      AND organisation_id=:organisationId AND school_id=:schoolId
                      AND payment_provider_config_id=:providerId AND provider_code=:providerCode AND provider_environment=:environment
                      AND merchant_account_reference=:merchant AND credentials_secret_reference=:credentialsReference
                      AND provider_checkout_reference=:attemptId ORDER BY retained_at,id LIMIT 2
                    """, bindingParameters(p.addValue("accountId", snapshot.accountId()), snapshot.binding()),
                    (r,n)->new PrivateLink(r.getString("url_ciphertext"),r.getString("url_nonce"),r.getString("url_sha256")));
            if (rows.size()!=1) throw unavailable(); // Append-only: no UPDATE-dependent FOR SHARE policy.
            String aad = GatewayHostedCheckoutStore.associatedData(snapshot.binding(), attemptId);
            var link = rows.getFirst();
            plain = cipher.decrypt(new SensitiveValueCipher.EncryptedValue(link.ciphertext(),link.nonce()),aad);
            if (plain==null || plain.length<1 || plain.length>250) throw unavailable();
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(plain)).toString();
            URI uri = GatewayHostedCheckoutStore.requireHostedUri(snapshot.binding(), URI.create(text));
            byte[] expected = MessageDigest.getInstance("SHA-256").digest((aad+"\n"+uri.toASCIIString()).getBytes(StandardCharsets.UTF_8));
            if (link.hash()==null || !link.hash().matches("[a-f0-9]{64}")
                    || !MessageDigest.isEqual(expected,HexFormat.of().parseHex(link.hash()))) throw unavailable();
            // Cipher work and audit-lock waiting cannot extend a checkout or stale grant.
            var checked = eligible(user, access.requireChild(user, learnerId), attemptId);
            if (!snapshot.equals(checked)) throw unavailable();
            audit.recordScoped(user, child.membershipId(), child.schoolId(), "finance.gateway.checkout.continue_authorized",
                    "FEE_GATEWAY_ATTEMPT",attemptId.toString(),SecurityAuditService.AuditOutcome.SUCCEEDED,
                    "Initiating guardian requested the original hosted checkout",Map.of());
            if (!snapshot.equals(eligible(user, access.requireChild(user, learnerId), attemptId))) throw unavailable();
            return new Continuation(attemptId, uri, snapshot.expiresAt());
        } catch (ApiException denied) {
            if (denied.status()==HttpStatus.NOT_FOUND) throw denied;
            throw unavailable();
        } catch (Exception privateFailure) { throw unavailable(); }
        finally { if (plain!=null) Arrays.fill(plain,(byte)0); }
    }

    private Eligible eligible(AuthenticatedUser user, FamilyGatewayAccess.Scope child, UUID attemptId) {
        if (!registry.creationEnabled() || !registry.workerEnabled()) return null;
        var p = parameters(user,child,attemptId);
        // The finance fence serializes every writer in actual continuation. Read
        // hints use the same predicates but cannot promise a later POST succeeds.
        var rows = jdbc.query("""
                SELECT attempt.learner_fee_account_id,attempt.expires_at,provider.id AS provider_id,
                  provider.provider_code,provider.environment,provider.merchant_account_reference,provider.credentials_secret_reference
                FROM fee_gateway_attempts attempt JOIN learner_fee_accounts account ON account.id=attempt.learner_fee_account_id
                  AND account.organisation_id=attempt.organisation_id AND account.school_id=attempt.school_id
                JOIN payment_provider_configs provider ON provider.id=attempt.payment_provider_config_id
                  AND provider.organisation_id=attempt.organisation_id AND provider.school_id=attempt.school_id
                  AND provider.provider_code=attempt.provider_code AND provider.environment=attempt.provider_environment
                  AND provider.merchant_account_reference=attempt.merchant_account_reference AND provider.status='ACTIVE'
                JOIN fee_gateway_attempt_work work ON work.attempt_id=attempt.id AND work.organisation_id=attempt.organisation_id
                  AND work.school_id=attempt.school_id AND work.payment_provider_config_id=provider.id
                  AND work.provider_code=provider.provider_code AND work.provider_environment=provider.environment
                  AND work.merchant_account_reference=provider.merchant_account_reference
                  AND work.credentials_secret_reference=provider.credentials_secret_reference
                WHERE attempt.id=:attemptId AND attempt.organisation_id=:organisationId AND attempt.school_id=:schoolId
                  AND account.learner_id=:learnerId AND account.status='ACTIVE' AND account.currency_code='INR'
                  AND attempt.initiated_by_user_id=:userId AND attempt.status IN ('RESERVED','CHECKOUT_PENDING')
                  AND attempt.fee_payment_id IS NULL AND attempt.expires_at>clock_timestamp()
                  AND current_app_user_id()=:userId AND current_app_organisation_id()=:organisationId
                  AND work.create_dispatched_at IS NOT NULL AND work.status IN ('READY','LEASED','RETRY')
                  AND NOT EXISTS(SELECT 1 FROM fee_day_closes close WHERE close.organisation_id=:organisationId
                    AND close.school_id=:schoolId AND close.status='APPROVED'
                    AND close.business_date>=LEAST(attempt.original_business_date,(clock_timestamp() AT TIME ZONE attempt.school_time_zone)::date))
                  AND (SELECT count(*) FROM fee_gateway_hosted_checkouts link WHERE link.attempt_id=attempt.id
                    AND link.organisation_id=:organisationId AND link.school_id=:schoolId)=1
                  AND EXISTS(SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
                    AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId AND allocation.status='ACTIVE')
                  AND (SELECT COALESCE(sum(allocation.amount),0) FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
                    AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId AND allocation.status='ACTIVE')=attempt.amount
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_allocations allocation WHERE allocation.attempt_id=attempt.id
                    AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId AND allocation.status<>'ACTIVE')
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_allocations allocation JOIN learner_fee_charges charge
                    ON charge.id=allocation.learner_fee_charge_id AND charge.learner_fee_account_id=allocation.learner_fee_account_id
                      AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
                    WHERE allocation.attempt_id=attempt.id AND allocation.organisation_id=:organisationId AND allocation.school_id=:schoolId
                      AND (SELECT COALESCE(sum(reserved.amount),0) FROM fee_gateway_allocations reserved
                        WHERE reserved.learner_fee_charge_id=charge.id AND reserved.organisation_id=:organisationId
                          AND reserved.school_id=:schoolId AND reserved.status='ACTIVE')>(charge.amount
                        -COALESCE((SELECT sum(entry.amount) FROM fee_ledger_entries entry WHERE entry.learner_fee_charge_id=charge.id
                          AND entry.organisation_id=:organisationId AND entry.school_id=:schoolId AND entry.entry_type='CONCESSION'),0)
                        -fee_charge_net_paid(charge.organisation_id,charge.school_id,charge.id,NULL::date)))
                  AND (SELECT COALESCE(sum(reserved.amount),0) FROM fee_gateway_allocations reserved
                    WHERE reserved.learner_fee_account_id=account.id AND reserved.organisation_id=:organisationId
                      AND reserved.school_id=:schoolId AND reserved.status='ACTIVE') <= (SELECT COALESCE(sum(CASE entry.direction
                        WHEN 'DEBIT' THEN entry.amount ELSE -entry.amount END),0) FROM fee_ledger_entries entry
                        WHERE entry.learner_fee_account_id=account.id AND entry.organisation_id=:organisationId AND entry.school_id=:schoolId)
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_captures capture WHERE capture.organisation_id=:organisationId AND capture.school_id=:schoolId
                    AND capture.payment_provider_config_id=provider.id AND (capture.attempt_id=attempt.id OR capture.checkout_reference=attempt.id::text
                      OR EXISTS(SELECT 1 FROM fee_gateway_checkout_observations observation WHERE observation.attempt_id=attempt.id
                        AND observation.organisation_id=:organisationId AND observation.school_id=:schoolId AND capture.order_reference=ANY(observation.order_references))))
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_checkout_observations observation WHERE observation.attempt_id=attempt.id
                    AND observation.organisation_id=:organisationId AND observation.school_id=:schoolId
                    AND (observation.amount_paid>0 OR observation.successful_payment_count>0
                      OR observation.checkout_status IN ('PAID','PARTIALLY_PAID','CANCELLED','EXPIRED')))
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_checkout_observations uncertain WHERE uncertain.attempt_id=attempt.id
                    AND uncertain.organisation_id=:organisationId AND uncertain.school_id=:schoolId
                    AND (uncertain.pending_payment_count>0 OR NOT uncertain.discovery_complete)
                    AND NOT EXISTS(SELECT 1 FROM fee_gateway_checkout_observations later WHERE later.attempt_id=attempt.id
                      AND later.organisation_id=:organisationId AND later.school_id=:schoolId AND later.discovery_complete
                      AND later.pending_payment_count=0 AND later.successful_payment_count=0 AND later.amount_paid=0
                      AND later.order_references @> uncertain.order_references
                      AND COALESCE(gateway_instant_epoch_ns(later.observed_at_exact),extract(epoch FROM later.observed_at)*1000000000)
                        > COALESCE(gateway_instant_epoch_ns(uncertain.observed_at_exact),extract(epoch FROM uncertain.observed_at)*1000000000)))
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_payment_observations fact WHERE fact.organisation_id=:organisationId AND fact.school_id=:schoolId
                    AND fact.payment_provider_config_id=provider.id AND (fact.captured OR fact.payment_status='SUCCESS'
                      OR (fact.payment_status='PENDING' AND NOT EXISTS(SELECT 1 FROM fee_gateway_checkout_observations later
                        WHERE later.attempt_id=attempt.id AND later.organisation_id=:organisationId AND later.school_id=:schoolId
                          AND later.discovery_complete AND later.pending_payment_count=0 AND later.successful_payment_count=0 AND later.amount_paid=0
                          AND fact.order_reference=ANY(later.order_references)
                          AND COALESCE(gateway_instant_epoch_ns(later.observed_at_exact),extract(epoch FROM later.observed_at)*1000000000)
                            > extract(epoch FROM fact.observed_at)*1000000000)))
                    AND (fact.attempt_id=attempt.id OR EXISTS(SELECT 1 FROM fee_gateway_checkout_observations observation
                      WHERE observation.attempt_id=attempt.id AND observation.organisation_id=:organisationId AND observation.school_id=:schoolId
                        AND fact.order_reference=ANY(observation.order_references))))
                  AND NOT EXISTS(SELECT 1 FROM fee_gateway_webhook_inbox inbox WHERE inbox.organisation_id=:organisationId AND inbox.school_id=:schoolId
                    AND inbox.payment_provider_config_id=provider.id AND inbox.event_type='PAYMENT_SUCCESS_WEBHOOK'
                    AND (inbox.checkout_reference=attempt.id OR EXISTS(SELECT 1 FROM fee_gateway_checkout_observations observation
                      WHERE observation.attempt_id=attempt.id AND observation.organisation_id=:organisationId AND observation.school_id=:schoolId
                        AND inbox.order_reference=ANY(observation.order_references))))
                """,p,(r,n)->new Eligible(r.getObject("learner_fee_account_id",UUID.class),r.getObject("expires_at",OffsetDateTime.class).toInstant(),
                        new GatewayWorkQueue.ProviderBinding(child.organisationId(),child.schoolId(),r.getObject("provider_id",UUID.class),
                                r.getString("provider_code"),r.getString("environment"),r.getString("merchant_account_reference"),r.getString("credentials_secret_reference"))));
        if (rows.size()!=1) return null;
        Eligible value = rows.getFirst();
        var matches = registry.configuredRoutes().stream().filter(route -> sameRoute(route,value.binding())).toList();
        if (matches.size()!=1 || "LIVE".equals(value.binding().environment()) && !registry.liveEnabled()) return null;
        return value;
    }

    private static boolean sameRoute(GatewayProviderRegistry.Route route, GatewayWorkQueue.ProviderBinding binding) {
        return route.organisationId().equals(binding.organisationId()) && route.schoolId().equals(binding.schoolId())
                && route.providerConfigId().equals(binding.providerConfigId()) && route.providerCode().equals(binding.providerCode())
                && route.environment().name().equals(binding.environment()) && route.merchantAccountReference().equals(binding.merchantReference())
                && route.credentialsSecretReference().equals(binding.credentialsReference());
    }
    private static MapSqlParameterSource parameters(AuthenticatedUser user, FamilyGatewayAccess.Scope scope, UUID attemptId) {
        return new MapSqlParameterSource("organisationId",scope.organisationId()).addValue("schoolId",scope.schoolId())
                .addValue("learnerId",scope.learnerId()).addValue("userId",user.userId()).addValue("attemptId",attemptId);
    }
    private static MapSqlParameterSource bindingParameters(MapSqlParameterSource p, GatewayWorkQueue.ProviderBinding b) {
        return p.addValue("providerId",b.providerConfigId()).addValue("providerCode",b.providerCode()).addValue("environment",b.environment())
                .addValue("merchant",b.merchantReference()).addValue("credentialsReference",b.credentialsReference());
    }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.CONFLICT,"FAMILY_CHECKOUT_UNAVAILABLE",
                "This checkout cannot be continued. Refresh the verified payment status or contact the school before paying again.");
    }
    private record Eligible(UUID accountId,Instant expiresAt,GatewayWorkQueue.ProviderBinding binding) {
        @Override public String toString(){ return "Eligible[private checkout eligibility]"; }
    }
    private record PrivateLink(String ciphertext,String nonce,String hash) {
        @Override public String toString(){ return "PrivateLink[encrypted hosted evidence]"; }
    }
}
