package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SensitiveValueCipher;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Arrays;
import java.util.HexFormat;
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
 * Commit the exact scoped school intent before any provider network call. This internal
 * service neither activates a provider nor accepts a browser claim as fee payment.
 */
@Service
public class GatewayReservationService {
    private static final Duration CHECKOUT_TTL = Duration.ofMinutes(30);
    private final NamedParameterJdbcTemplate jdbc;
    private final FamilyFinanceService family;
    private final SecurityAuditService audit;
    private final SensitiveValueCipher cipher;
    private final ApplicationProperties properties;
    private final Clock clock;

    public GatewayReservationService(NamedParameterJdbcTemplate jdbc, FamilyFinanceService family,
            SecurityAuditService audit, SensitiveValueCipher cipher, ApplicationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.family = family;
        this.audit = audit;
        this.cipher = cipher;
        this.properties = properties;
        this.clock = clock;
    }

    /** Choose precision before persisting NEW intent only; never re-normalize historical evidence. */
    static Instant newCheckoutExpiresAt(Instant createdAt) {
        return Objects.requireNonNull(createdAt, "Intent creation instant required").plus(CHECKOUT_TTL)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    public record ChargeSelection(UUID chargeId, BigDecimal amount) {
        public ChargeSelection {
            Objects.requireNonNull(chargeId, "Selected charge required");
            PaymentProviderAdapter.requireAmount(amount);
        }
    }

    public record PrepareRequest(UUID clientRequestId, List<ChargeSelection> allocations, String payerPhone, UUID accountId) {
        /** Omitted account selection preserves the existing current-session contract. */
        public PrepareRequest(UUID clientRequestId, List<ChargeSelection> allocations, String payerPhone) {
            this(clientRequestId, allocations, payerPhone, null);
        }
        public PrepareRequest {
            Objects.requireNonNull(clientRequestId, "Stable request identity required");
            if (allocations == null || allocations.isEmpty() || allocations.size() > 100
                    || allocations.stream().anyMatch(Objects::isNull)
                    || allocations.stream().map(ChargeSelection::chargeId).distinct().count() != allocations.size()
                    || payerPhone == null || !payerPhone.matches("[0-9]{10}")) {
                throw new IllegalArgumentException("Unique charge selections and Indian payer phone required");
            }
            allocations = allocations.stream().sorted(Comparator.comparing(ChargeSelection::chargeId)).toList();
            PaymentProviderAdapter.requireAmount(allocations.stream().map(ChargeSelection::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        public String accountSelectionMode() { return accountId == null ? "CURRENT_SESSION" : "EXPLICIT_ACCOUNT"; }
        @Override public String toString() { return "PrepareRequest[sensitive payment intent]"; }
    }

    public record PlannedAttempt(UUID id, String status, BigDecimal amount, String currencyCode,
            LocalDate originalBusinessDate, Instant expiresAt, long version) {}

    /**
     * Validate an acknowledged immutable request against its ORIGINAL account,
     * including after session rollover. No current-account selection, enqueue,
     * provider lookup, new reservation or financial mutation occurs here.
     */
    @Transactional
    public PlannedAttempt replay(AuthenticatedUser user, UUID learnerId, UUID attemptId, PrepareRequest request) {
        FamilyGatewayAccess.requireActor(user);
        requireTransaction(user);
        if (learnerId == null || attemptId == null || request == null) throw FamilyGatewayAccess.missing();
        var p = new MapSqlParameterSource().addValue("organisationId", user.activeOrganisationId())
                .addValue("userId", user.userId()).addValue("attemptId", attemptId);
        var original = jdbc.query("""
                SELECT id,learner_fee_account_id,client_request_id,status,amount,currency_code,
                  original_business_date,expires_at,row_version,request_fingerprint,payer_phone_ciphertext,payer_phone_nonce,account_selection_mode
                FROM fee_gateway_attempts WHERE id=:attemptId AND organisation_id=:organisationId AND initiated_by_user_id=:userId
                  AND current_app_organisation_id()=:organisationId AND current_app_user_id()=:userId
                """, p, row -> {
                    if (!row.next()) return null;
                    var value = new Replay(new StoredAttempt(new PlannedAttempt(row.getObject("id", UUID.class), row.getString("status"),
                            row.getBigDecimal("amount"), row.getString("currency_code"), row.getObject("original_business_date", LocalDate.class),
                            row.getObject("expires_at", java.time.OffsetDateTime.class).toInstant(), row.getLong("row_version")),
                            row.getObject("learner_fee_account_id", UUID.class), row.getString("request_fingerprint"),
                            row.getString("payer_phone_ciphertext"), row.getString("payer_phone_nonce"),
                            row.getString("account_selection_mode")), row.getObject("client_request_id", UUID.class));
                    if (row.next()) throw FamilyGatewayAccess.missing();
                    return value;
                });
        if (original == null) throw FamilyGatewayAccess.missing();
        // Same stateless policy as the family API, not a second weaker guardian
        // query. It independently checks the actual actor, session membership,
        // clock-authored dated grant and old account's current own-child link.
        var account = new FamilyGatewayAccess(jdbc).requireAccount(user, learnerId, original.attempt().accountId());
        var stored = original.attempt();
        BigDecimal requested = request.allocations().stream().map(ChargeSelection::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (!request.clientRequestId().equals(original.requestId()) || !"INR".equals(stored.value().currencyCode())
                || !request.accountSelectionMode().equals(stored.selectionMode())
                || request.accountId() != null && !request.accountId().equals(stored.accountId())
                || requested.compareTo(stored.value().amount()) != 0
                || !fingerprint(stored.accountId(), request).equals(stored.fingerprint())) {
            throw conflict("ONLINE_REQUEST_REUSED", "This request identity belongs to different payment terms.");
        }
        byte[] decoded = null, expected = null;
        boolean matching;
        try {
            decoded = cipher.decrypt(new SensitiveValueCipher.EncryptedValue(stored.phoneCiphertext(), stored.phoneNonce()),
                    associatedData(account.scope().organisationId(), account.scope().schoolId(), stored.accountId(), stored.value().id()));
            expected = request.payerPhone().getBytes(StandardCharsets.UTF_8);
            matching = decoded != null && MessageDigest.isEqual(decoded, expected);
        } catch (RuntimeException unavailable) {
            // Crypto diagnostics may carry private values. Do not retain causes.
            throw conflict("ONLINE_REPLAY_UNAVAILABLE", "The original payment request is temporarily unavailable. Refresh or contact the school.");
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
            if (expected != null) Arrays.fill(expected, (byte) 0);
        }
        if (!matching) throw conflict("ONLINE_REQUEST_REUSED", "This request identity belongs to different payment terms.");
        return stored.value();
    }

    @Transactional
    public PlannedAttempt prepare(AuthenticatedUser user, UUID learnerId, PrepareRequest request) {
        requireTransaction(user);
        Objects.requireNonNull(request, "Validated payment intent required");
        // A historical account is opt-in, never a silent replacement for today's
        // account. Both paths reuse the same live own-child policy.
        FamilyFeesResponse fees = selectedFees(user, learnerId, request);
        if (fees.accountId() == null) throw conflict("ONLINE_FEE_ACCOUNT_UNAVAILABLE", "No current fee account is available.");
        var p = new MapSqlParameterSource().addValue("organisationId", user.activeOrganisationId())
                .addValue("accountId", fees.accountId()).addValue("userId", user.userId())
                .addValue("membershipIds", user.membershipIds()).addValue("requestId", request.clientRequestId());
        Account account = jdbc.query("""
                SELECT account.school_id,account.status,account.currency_code,school.time_zone
                FROM learner_fee_accounts account JOIN schools school ON school.id=account.school_id
                  AND school.organisation_id=account.organisation_id
                JOIN organisations organisation ON organisation.id=account.organisation_id
                WHERE account.id=:accountId AND account.organisation_id=:organisationId
                  AND school.status='ACTIVE' AND organisation.status='ACTIVE'
                """, p, r -> r.next() ? new Account(r.getObject("school_id", UUID.class), r.getString("status"),
                        r.getString("currency_code"), r.getString("time_zone")) : null);
        if (account == null) throw conflict("ONLINE_FEE_ACCOUNT_UNAVAILABLE", "The school's fee account is unavailable.");
        p.addValue("schoolId", account.schoolId());
        lockFinance(p, account.schoolId());
        // Reauthorize after waiting on competing finance work. An old browser snapshot is not authority.
        if (!fees.accountId().equals(selectedFees(user, learnerId, request).accountId())) {
            throw conflict("ONLINE_FEE_ACCOUNT_CHANGED", "Refresh the child's current fee account.");
        }
        String fingerprint = fingerprint(fees.accountId(), request);
        StoredAttempt existing = jdbc.query("""
                SELECT id,status,amount,currency_code,original_business_date,expires_at,row_version,
                  learner_fee_account_id,request_fingerprint,payer_phone_ciphertext,payer_phone_nonce,account_selection_mode
                FROM fee_gateway_attempts WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND initiated_by_user_id=:userId AND client_request_id=:requestId
                """, p, r -> r.next() ? new StoredAttempt(new PlannedAttempt(r.getObject("id", UUID.class),
                        r.getString("status"), r.getBigDecimal("amount"), r.getString("currency_code"),
                        r.getObject("original_business_date", LocalDate.class), r.getTimestamp("expires_at").toInstant(),
                        r.getLong("row_version")), r.getObject("learner_fee_account_id", UUID.class),
                        r.getString("request_fingerprint"), r.getString("payer_phone_ciphertext"),
                        r.getString("payer_phone_nonce"), r.getString("account_selection_mode")) : null);
        if (existing != null) {
            if (!fees.accountId().equals(existing.accountId()) || !request.accountSelectionMode().equals(existing.selectionMode())
                    || !fingerprint.equals(existing.fingerprint())
                    || !request.payerPhone().equals(new String(cipher.decrypt(new SensitiveValueCipher.EncryptedValue(
                            existing.phoneCiphertext(), existing.phoneNonce()), associatedData(user.activeOrganisationId(),
                            account.schoolId(), existing.accountId(), existing.value().id())), StandardCharsets.UTF_8))) {
                throw conflict("ONLINE_REQUEST_REUSED", "This request identity belongs to different payment terms.");
            }
            return existing.value();
        }
        String activeStatus = jdbc.queryForObject("""
                SELECT status FROM learner_fee_accounts WHERE id=:accountId
                  AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """, p, String.class);
        if (!"ACTIVE".equals(activeStatus) || !"INR".equals(account.currencyCode())) {
            throw conflict("ONLINE_FEE_ACCOUNT_UNAVAILABLE", "An active Indian-rupee fee account is required.");
        }
        List<UUID> memberships = jdbc.query("""
                SELECT DISTINCT membership.id FROM memberships membership
                JOIN user_accounts issuer ON issuer.id=membership.user_id AND issuer.status='ACTIVE'
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN roles role ON role.id=mr.role_id
                JOIN role_capabilities rc ON rc.role_id=mr.role_id AND rc.capability_code='portal.guardian'
                WHERE membership.id IN(:membershipIds) AND membership.user_id=:userId
                  AND membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                  AND membership.status='ACTIVE' AND membership.valid_from<=clock_timestamp()
                  AND (membership.valid_until IS NULL OR membership.valid_until>clock_timestamp())
                  AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                ORDER BY membership.id LIMIT 1
                """, p, (r, n) -> r.getObject("id", UUID.class));
        if (memberships.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "ONLINE_FAMILY_SCOPE_DENIED", "Family fee access is unavailable.");
        List<Provider> providers = jdbc.query("""
                SELECT id,provider_code,environment,merchant_account_reference FROM payment_provider_configs
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                ORDER BY id FOR UPDATE
                """, p, (r, n) -> new Provider(r.getObject("id", UUID.class), r.getString("provider_code"),
                        r.getString("environment"), r.getString("merchant_account_reference")));
        if (providers.size() != 1 || !"CASHFREE".equals(providers.getFirst().code())
                || providers.getFirst().merchant() == null || !providers.getFirst().merchant().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,119}")) {
            throw conflict("ONLINE_PROVIDER_UNAVAILABLE", "One approved supported gateway configuration is required.");
        }
        Provider provider = providers.getFirst();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Instant expires = newCheckoutExpiresAt(now);
        LocalDate originalDate = now.atZone(ZoneId.of(account.timeZone())).toLocalDate();
        LocalDate closedThrough = jdbc.queryForObject("""
                SELECT max(business_date) FROM fee_day_closes WHERE organisation_id=:organisationId
                  AND school_id=:schoolId AND status='APPROVED'
                """, p, LocalDate.class);
        if (closedThrough != null && !originalDate.isAfter(closedThrough)) {
            throw conflict("ONLINE_FINANCIAL_DATE_CLOSED", "Today's financial day is already closed. Ask the school before starting another payment.");
        }
        Integer sequences = jdbc.queryForObject("""
                SELECT count(*) FROM fee_receipt_sequences sequence JOIN learner_fee_accounts account
                  ON account.academic_session_id=sequence.academic_session_id
                  AND account.organisation_id=sequence.organisation_id AND account.school_id=sequence.school_id
                WHERE account.id=:accountId AND account.organisation_id=:organisationId AND account.school_id=:schoolId
                """, p, Integer.class);
        if (sequences == null || sequences != 1) throw conflict("ONLINE_RECEIPT_UNAVAILABLE", "The school must configure its numbered receipts before accepting payment.");
        for (ChargeSelection input : request.allocations()) {
            p.addValue("chargeId", input.chargeId());
            Charge charge = jdbc.query("""
                    SELECT charge.amount,charge.allow_partial,
                      COALESCE((SELECT sum(entry.amount) FROM fee_ledger_entries entry
                        WHERE entry.learner_fee_charge_id=charge.id AND entry.entry_type='CONCESSION'),0) concessions,
                      fee_charge_net_paid(charge.organisation_id,charge.school_id,charge.id,NULL::date) paid,
                      COALESCE((SELECT sum(reservation.amount) FROM fee_gateway_allocations reservation
                        WHERE reservation.learner_fee_charge_id=charge.id AND reservation.status='ACTIVE'),0) reserved
                    FROM learner_fee_charges charge WHERE charge.id=:chargeId AND charge.learner_fee_account_id=:accountId
                      AND charge.organisation_id=:organisationId AND charge.school_id=:schoolId FOR UPDATE OF charge
                    """, p, r -> r.next() ? new Charge(r.getBigDecimal("amount"), r.getBigDecimal("concessions"),
                            r.getBigDecimal("paid"), r.getBigDecimal("reserved"), r.getBoolean("allow_partial")) : null);
            if (charge == null) throw new ApiException(HttpStatus.NOT_FOUND, "ONLINE_FEE_CHARGE_NOT_FOUND", "A selected fee charge is unavailable.");
            BigDecimal outstanding = charge.amount().subtract(charge.concessions()).subtract(charge.paid());
            BigDecimal available = outstanding.subtract(charge.reserved()).max(BigDecimal.ZERO);
            if (input.amount().compareTo(available) > 0) throw conflict("ONLINE_FEE_RESERVED_OR_PAID", "The selected amount is already paid or reserved by another pending payment.");
            if (!charge.allowPartial() && input.amount().compareTo(outstanding) != 0) {
                throw conflict("PARTIAL_PAYMENT_DISABLED", "This installment must be paid in full.");
            }
        }
        BigDecimal amount = request.allocations().stream().map(ChargeSelection::amount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
        BigDecimal availableBalance = jdbc.queryForObject("""
                SELECT (SELECT COALESCE(sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END),0)
                    FROM fee_ledger_entries WHERE learner_fee_account_id=:accountId
                      AND organisation_id=:organisationId AND school_id=:schoolId)
                  -(SELECT COALESCE(sum(amount),0) FROM fee_gateway_allocations WHERE learner_fee_account_id=:accountId
                      AND organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE')
                """, p, BigDecimal.class);
        if (availableBalance == null || amount.compareTo(availableBalance) > 0) {
            throw conflict("ONLINE_FEE_EXCEEDS_BALANCE", "The selected amount exceeds the current available account balance.");
        }
        UUID id = UUID.randomUUID();
        URI returnUri = returnUri(provider.environment(), id);
        SensitiveValueCipher.EncryptedValue phone = cipher.encrypt(request.payerPhone().getBytes(StandardCharsets.UTF_8),
                associatedData(user.activeOrganisationId(), account.schoolId(), fees.accountId(), id));
        p.addValue("id", id).addValue("membershipId", memberships.getFirst()).addValue("providerId", provider.id())
                .addValue("providerCode", provider.code()).addValue("environment", provider.environment())
                .addValue("merchant", provider.merchant()).addValue("timeZone", account.timeZone()).addValue("fingerprint", fingerprint).addValue("amount", amount)
                .addValue("phoneCiphertext", phone.ciphertext()).addValue("phoneNonce", phone.nonce())
                .addValue("returnUri", returnUri.toASCIIString()).addValue("date", originalDate).addValue("now", now).addValue("expires", expires)
                .addValue("selectionMode", request.accountSelectionMode());
        jdbc.update("""
                INSERT INTO fee_gateway_attempts(id,organisation_id,school_id,learner_fee_account_id,payment_provider_config_id,
                  initiated_by_user_id,initiated_by_membership_id,client_request_id,request_fingerprint,checkout_reference,
                  amount,currency_code,payer_phone_ciphertext,payer_phone_nonce,return_uri,provider_code,provider_environment,
                  merchant_account_reference,school_time_zone,expires_at,original_business_date,status,created_at,updated_at,row_version,account_selection_mode)
                VALUES(:id,:organisationId,:schoolId,:accountId,:providerId,:userId,:membershipId,:requestId,:fingerprint,:id,
                  :amount,'INR',:phoneCiphertext,:phoneNonce,:returnUri,:providerCode,:environment,:merchant,:timeZone,:expires,:date,'RESERVED',:now,:now,0,:selectionMode)
                """, p);
        for (ChargeSelection input : request.allocations()) {
            jdbc.update("""
                    INSERT INTO fee_gateway_allocations(id,attempt_id,learner_fee_account_id,learner_fee_charge_id,
                      organisation_id,school_id,amount,status,created_at,updated_at,row_version)
                    VALUES(:allocationId,:id,:accountId,:chargeId,:organisationId,:schoolId,:allocated,'ACTIVE',:now,:now,0)
                    """, p.addValue("allocationId", UUID.randomUUID()).addValue("chargeId", input.chargeId()).addValue("allocated", input.amount()));
        }
        audit.recordScoped(user, memberships.getFirst(), account.schoolId(), "finance.gateway.intent.reserved", "FEE_GATEWAY_ATTEMPT",
                id.toString(), SecurityAuditService.AuditOutcome.SUCCEEDED, "Family initiated hosted fee checkout",
                java.util.Map.of("provider", provider.code(), "environment", provider.environment(), "allocationCount", request.allocations().size()));
        return new PlannedAttempt(id, "RESERVED", amount, "INR", originalDate, expires, 0);
    }

    private FamilyFeesResponse selectedFees(AuthenticatedUser user, UUID learnerId, PrepareRequest request) {
        return request.accountId() == null ? family.fees(user, learnerId) : family.fees(user, learnerId, request.accountId());
    }

    private void requireTransaction(AuthenticatedUser user) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Gateway reservation requires a writable transaction");
        }
        if (user == null || user.userId() == null || user.activeOrganisationId() == null || user.membershipIds().isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ONLINE_FAMILY_SCOPE_DENIED", "Family fee access is unavailable.");
        }
        Boolean scoped = jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId",
                new MapSqlParameterSource().addValue("organisationId", user.activeOrganisationId()), Boolean.class);
        if (!Boolean.TRUE.equals(scoped)) throw new ApiException(HttpStatus.FORBIDDEN, "ONLINE_FAMILY_SCOPE_DENIED", "Family fee access is unavailable.");
    }

    private static void lockFinance(MapSqlParameterSource p, UUID schoolId, NamedParameterJdbcTemplate jdbc) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))", p.addValue("lockKey", schoolId + ":finance"), (r, n) -> true);
    }
    private void lockFinance(MapSqlParameterSource p, UUID schoolId) { lockFinance(p, schoolId, jdbc); }

    private URI returnUri(String environment, UUID id) {
        try {
            URI origin = URI.create(properties.publicUrl());
            boolean loopback = List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(origin.getHost());
            if (origin.getHost() == null || origin.getRawUserInfo() != null || origin.getRawQuery() != null
                    || origin.getRawFragment() != null || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/"))
                    || origin.getPort() == 0 || origin.getPort() < -1 || origin.getPort() > 65535
                    || !("https".equals(origin.getScheme()) || "TEST".equals(environment) && loopback && "http".equals(origin.getScheme()))) {
                throw new IllegalArgumentException();
            }
            URI result = URI.create(origin.getScheme() + "://" + origin.getRawAuthority() + "/family/payments/" + id);
            if (result.toASCIIString().length() > 250) throw new IllegalArgumentException();
            return result;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw conflict("ONLINE_RETURN_URL_UNAVAILABLE", "The school must configure a safe family return address.");
        }
    }

    static String associatedData(UUID organisation, UUID school, UUID account, UUID attempt) {
        return "finance:gateway:payer-phone:" + organisation + ":" + school + ":" + account + ":" + attempt;
    }
    private static String fingerprint(UUID account, PrepareRequest request) {
        // Preserve every historical legacy hash; explicit selection has its own
        // versioned immutable terms, including the original exact account.
        String prefix = request.accountId() == null ? "" : "v2|EXPLICIT_ACCOUNT|";
        String canonical = prefix + account + "|INR|" + String.join(",", request.allocations().stream().map(a -> a.chargeId() + ":" + a.amount().setScale(2).toPlainString()).toList());
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private record Account(UUID schoolId, String status, String currencyCode, String timeZone) {}
    private record Provider(UUID id, String code, String environment, String merchant) {}
    private record Charge(BigDecimal amount, BigDecimal concessions, BigDecimal paid, BigDecimal reserved, boolean allowPartial) {}
    private record StoredAttempt(PlannedAttempt value, UUID accountId, String fingerprint, String phoneCiphertext, String phoneNonce, String selectionMode) {}
    private record Replay(StoredAttempt attempt, UUID requestId) {
        @Override public String toString() { return "Replay[private original family payment terms]"; }
    }
}
