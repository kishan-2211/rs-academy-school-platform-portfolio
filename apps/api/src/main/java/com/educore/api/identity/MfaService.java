package com.educore.api.identity;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final String MFA_ERROR = "The verification code is invalid or has expired.";
    private final MfaTotpFactorRepository factors;
    private final MfaRecoveryCodeRepository recoveryCodes;
    private final MfaLoginChallengeRepository challenges;
    private final UserAccountRepository users;
    private final TotpService totp;
    private final SensitiveValueCipher cipher;
    private final SessionTokenCodec tokenCodec;
    private final SecureRandom secureRandom;
    private final SecurityAuditService audit;
    private final ApplicationProperties properties;
    private final Clock clock;

    public MfaService(
            MfaTotpFactorRepository factors,
            MfaRecoveryCodeRepository recoveryCodes,
            MfaLoginChallengeRepository challenges,
            UserAccountRepository users,
            TotpService totp,
            SensitiveValueCipher cipher,
            SessionTokenCodec tokenCodec,
            SecureRandom secureRandom,
            SecurityAuditService audit,
            ApplicationProperties properties,
            Clock clock) {
        this.factors = factors;
        this.recoveryCodes = recoveryCodes;
        this.challenges = challenges;
        this.users = users;
        this.totp = totp;
        this.cipher = cipher;
        this.tokenCodec = tokenCodec;
        this.secureRandom = secureRandom;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    boolean isEnabled(UUID userId) {
        return factors.countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE) > 0;
    }

    @Transactional(noRollbackFor = ApiException.class)
    MfaChallengeStart beginLogin(UUID userId, boolean remember, String userAgent) {
        users.findByIdForUpdate(userId).orElseThrow(() -> invalidChallenge(userId));
        Instant now = Instant.now(clock);
        if (challenges.countByUserIdAndConsumedAtIsNullAndExpiresAtAfter(userId, now) >= 5) {
            audit.record(userId, "identity.mfa.challenge.denied", AuditOutcome.DENIED, "Too many active challenges", Map.of());
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "MFA_CHALLENGE_LIMIT", "Wait for an earlier verification challenge to expire.");
        }
        String rawToken = tokenCodec.newRawToken();
        MfaLoginChallenge challenge = MfaLoginChallenge.create(
                userId,
                tokenCodec.hash(rawToken),
                remember,
                userAgent,
                now,
                properties.security().mfaChallengeTtl());
        challenges.save(challenge);
        audit.record(userId, "identity.mfa.challenge.created", AuditOutcome.SUCCEEDED, null, Map.of("challengeId", challenge.getId()));
        return new MfaChallengeStart(rawToken, challenge.getExpiresAt());
    }

    @Transactional(noRollbackFor = ApiException.class)
    MfaLoginCompletion verifyLoginChallenge(String rawChallengeToken, String suppliedCode) {
        String hash = tokenCodec.hash(rawChallengeToken);
        UUID ownerId = challenges.findOwnerByTokenHash(hash).orElseThrow(() -> invalidChallenge(null));
        // Consistent account -> challenge -> factor/code lock order, also used by password login.
        users.findByIdForUpdate(ownerId).orElseThrow(() -> invalidChallenge(ownerId));
        MfaLoginChallenge challenge = challenges.findByTokenHash(hash).orElseThrow(() -> invalidChallenge(ownerId));
        Instant now = Instant.now(clock);
        if (!ownerId.equals(challenge.getUserId()) || !challenge.isUsableAt(now)) throw invalidChallenge(ownerId);

        boolean verified = verifyAnyFactor(challenge.getUserId(), suppliedCode, now);
        if (!verified) {
            challenge.recordFailure(now);
            audit.record(challenge.getUserId(), "identity.mfa.challenge.denied", AuditOutcome.DENIED, "Invalid second factor", Map.of("challengeId", challenge.getId()));
            throw invalidChallenge(challenge.getUserId());
        }

        challenge.consume(now);
        audit.record(challenge.getUserId(), "identity.mfa.challenge.verified", AuditOutcome.SUCCEEDED, null, Map.of("challengeId", challenge.getId()));
        return new MfaLoginCompletion(
                challenge.getUserId(),
                challenge.isRememberSession(),
                challenge.getUserAgentSummary());
    }

    @Transactional(noRollbackFor = ApiException.class)
    TotpEnrollmentResponse startTotpEnrollment(UUID userId, String label, boolean sessionMfaVerified) {
        UserAccount user = lockEnrollmentAccount(userId, sessionMfaVerified);
        if (factors.countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE) >= 5) {
            throw new ApiException(HttpStatus.CONFLICT, "MFA_FACTOR_LIMIT", "Remove an existing authenticator before adding another one.");
        }
        Instant now = Instant.now(clock);
        TotpService.GeneratedSecret generated = totp.generateSecret();
        UUID factorId = UUID.randomUUID();
        String associatedData = userId + ":" + factorId;
        SensitiveValueCipher.EncryptedValue encrypted = cipher.encrypt(generated.bytes(), associatedData);
        for (MfaTotpFactor pending : factors.findForUpdate(userId, MfaFactorStatus.PENDING)) {
            pending.supersedePending();
            audit.record(userId, "identity.mfa.enrollment.superseded", AuditOutcome.SUCCEEDED,
                    "Pending setup replaced", Map.of("factorId", pending.getId()));
        }
        // Execute pending supersession before Hibernate queues its new INSERT.
        // The unique one-pending-per-user constraint must stay intact, not be relaxed.
        factors.flush();
        MfaTotpFactor factor = factors.save(MfaTotpFactor.pending(factorId, userId, label, encrypted, now));
        audit.record(userId, "identity.mfa.enrollment.started", AuditOutcome.SUCCEEDED, null, Map.of("factorId", factorId));
        return new TotpEnrollmentResponse(
                factorId,
                generated.encoded(),
                totp.provisioningUri(
                        properties.security().totpIssuer(),
                        user.getLoginIdentifierNormalized(),
                        generated.encoded()),
                factor.getCreatedAt().plus(properties.security().mfaEnrollmentTtl()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    RecoveryCodesResponse verifyTotpEnrollment(UUID userId, UUID factorId, String suppliedCode, boolean sessionMfaVerified) {
        lockEnrollmentAccount(userId, sessionMfaVerified);
        Instant now = Instant.now(clock);
        MfaTotpFactor factor = factors.findByIdAndUserId(factorId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MFA_ENROLLMENT_NOT_FOUND", "The MFA enrollment was not found."));
        if (!factor.canVerifyEnrollmentAt(now, properties.security().mfaEnrollmentTtl())) {
            throw new ApiException(HttpStatus.GONE, "MFA_ENROLLMENT_EXPIRED", "Start authenticator enrollment again.");
        }
        byte[] secret = cipher.decrypt(factor.encryptedSecret(), factor.associatedData());
        OptionalLong matchedStep = totp.matchingTimeStep(secret, suppliedCode, now);
        if (matchedStep.isEmpty()) {
            audit.record(userId, "identity.mfa.enrollment.denied", AuditOutcome.DENIED, "Invalid verification code", Map.of("factorId", factorId));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CODE", MFA_ERROR);
        }
        factor.activate(now, matchedStep.getAsLong());
        List<MfaRecoveryCode> superseded = recoveryCodes.findAvailableForUpdate(userId);
        superseded.forEach(code -> code.revokeUnused(now));
        List<String> rawRecoveryCodes = createRecoveryCodes(userId, now);
        audit.record(userId, "identity.mfa.enrollment.verified", AuditOutcome.SUCCEEDED, null,
                Map.of("factorId", factorId, "supersededRecoveryCodes", superseded.size()));
        return new RecoveryCodesResponse(rawRecoveryCodes);
    }

    @Transactional(readOnly = true)
    MfaStatusResponse status(UUID userId, boolean sessionMfaVerified) {
        List<MfaStatusResponse.Factor> activeFactors = factors
                .findByUserIdAndStatusOrderByCreatedAtAsc(userId, MfaFactorStatus.ACTIVE)
                .stream()
                .map(MfaStatusResponse.Factor::from)
                .toList();
        return new MfaStatusResponse(
                !activeFactors.isEmpty(),
                activeFactors,
                recoveryCodes.countByUserIdAndUsedAtIsNullAndRevokedAtIsNull(userId), sessionMfaVerified);
    }

    private UserAccount lockEnrollmentAccount(UUID userId, boolean sessionMfaVerified) {
        UserAccount user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "The account no longer exists."));
        // Serializes both setup stages before any pending-factor supersession, activation or recovery-code reset.
        if (!sessionMfaVerified && factors.countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE) > 0) {
            audit.record(userId, "identity.mfa.enrollment.denied", AuditOutcome.DENIED,
                    "An existing authenticator requires a verified session", Map.of());
            throw new ApiException(HttpStatus.FORBIDDEN, "MFA_VERIFICATION_REQUIRED",
                    "Sign in using an existing authenticator or recovery code before changing MFA setup.");
        }
        return user;
    }

    private boolean verifyAnyFactor(UUID userId, String suppliedCode, Instant now) {
        String normalizedCode = normalizeRecoveryCode(suppliedCode);
        if (normalizedCode.length() > 6) {
            return recoveryCodes.findByUserIdAndCodeHashAndUsedAtIsNullAndRevokedAtIsNull(userId, tokenCodec.hash(normalizedCode))
                    .map(code -> {
                        code.use(now);
                        return true;
                    })
                    .orElse(false);
        }

        for (MfaTotpFactor factor : factors.findForUpdate(userId, MfaFactorStatus.ACTIVE)) {
            byte[] secret = cipher.decrypt(factor.encryptedSecret(), factor.associatedData());
            OptionalLong matchedStep = totp.matchingTimeStep(secret, suppliedCode, now);
            if (matchedStep.isPresent() && factor.markUsed(matchedStep.getAsLong())) return true;
        }
        return false;
    }

    private List<String> createRecoveryCodes(UUID userId, Instant now) {
        List<String> rawCodes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int index = 0; index < RECOVERY_CODE_COUNT; index += 1) {
            byte[] entropy = new byte[16];
            secureRandom.nextBytes(entropy);
            String normalized = TotpService.encodeBase32(entropy);
            String displayed = normalized.replaceAll("(.{4})(?=.)", "$1-");
            recoveryCodes.save(MfaRecoveryCode.create(userId, tokenCodec.hash(normalized), now));
            rawCodes.add(displayed);
        }
        return rawCodes;
    }

    private static String normalizeRecoveryCode(String value) {
        return value == null ? "" : value.replace("-", "").strip().toUpperCase(Locale.ROOT);
    }

    private ApiException invalidChallenge(UUID userId) {
        if (userId != null) {
            audit.record(userId, "identity.mfa.challenge.denied", AuditOutcome.DENIED, "Invalid or expired challenge", Map.of());
        }
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_MFA_CHALLENGE", MFA_ERROR);
    }

    record MfaChallengeStart(String rawToken, Instant expiresAt) {
    }

    record MfaLoginCompletion(UUID userId, boolean remember, String userAgent) {
    }
}
