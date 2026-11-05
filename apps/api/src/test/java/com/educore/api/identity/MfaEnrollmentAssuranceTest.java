package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Lock ordering is explicit; real single-connection HTTP tests are not a concurrency proof. */
class MfaEnrollmentAssuranceTest {
    private final MfaTotpFactorRepository factors = mock(MfaTotpFactorRepository.class);
    private final MfaRecoveryCodeRepository recovery = mock(MfaRecoveryCodeRepository.class);
    private final MfaLoginChallengeRepository challenges = mock(MfaLoginChallengeRepository.class);
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final TotpService totp = mock(TotpService.class);
    private final SensitiveValueCipher cipher = mock(SensitiveValueCipher.class);
    private final SessionTokenCodec tokens = mock(SessionTokenCodec.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private final ApplicationProperties properties = mock(ApplicationProperties.class);
    private final ApplicationProperties.Security security = mock(ApplicationProperties.Security.class);
    private final Clock clock = Clock.systemUTC();
    private final MfaService service = new MfaService(factors, recovery, challenges, users, totp, cipher,
            tokens, new SecureRandom(), audit, properties, clock);
    private final UUID userId = UUID.randomUUID();
    private final UserAccount account = mock(UserAccount.class);

    MfaEnrollmentAssuranceTest() {
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(account));
        when(properties.security()).thenReturn(security);
        when(security.mfaEnrollmentTtl()).thenReturn(java.time.Duration.ofMinutes(10));
    }

    @Test void weakSessionCannotStartAnotherFactorOrDeletePendingEvidence() {
        when(factors.countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE)).thenReturn(1L);
        assertThatThrownBy(() -> service.startTotpEnrollment(userId, "Synthetic", false))
                .isInstanceOf(ApiException.class).hasMessageContaining("existing authenticator");
        var order = inOrder(users, factors);
        order.verify(users).findByIdForUpdate(userId);
        order.verify(factors).countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE);
        verify(factors, never()).findForUpdate(any(), any());
        verifyNoInteractions(totp, cipher, recovery);
    }

    @Test void weakSessionCannotActivatePendingFactorOrResetRecoveryAfterAnotherActivation() {
        UUID factorId = UUID.randomUUID();
        when(factors.countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE)).thenReturn(1L);
        assertThatThrownBy(() -> service.verifyTotpEnrollment(userId, factorId, "000000", false)).isInstanceOf(ApiException.class);
        var order = inOrder(users, factors);
        order.verify(users).findByIdForUpdate(userId);
        order.verify(factors).countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE);
        verify(factors, never()).findByIdAndUserId(any(), any());
        verifyNoInteractions(totp, cipher, recovery);
    }

    @Test void initialEnrollmentLocksBeforeReplacingPendingSetupAndVerifiedSessionsCanAddFactors() {
        var pending = MfaTotpFactor.pending(UUID.randomUUID(), userId, "Synthetic superseded",
                new SensitiveValueCipher.EncryptedValue("synthetic-cipher", "synthetic-nonce"), java.time.Instant.now(clock));
        when(factors.findForUpdate(userId, MfaFactorStatus.PENDING)).thenReturn(List.of(pending));
        when(totp.generateSecret()).thenReturn(new TotpService.GeneratedSecret(new byte[20], "SYNTHETIC"));
        when(cipher.encrypt(any(), anyString())).thenReturn(new SensitiveValueCipher.EncryptedValue("synthetic-cipher", "synthetic-nonce"));
        when(factors.save(any())).thenAnswer(call -> call.getArgument(0));
        assertThat(service.startTotpEnrollment(userId, "Synthetic", false).factorId()).isNotNull();
        var order = inOrder(users, factors, totp, cipher);
        order.verify(users).findByIdForUpdate(userId);
        order.verify(factors, times(2)).countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE);
        order.verify(totp).generateSecret();
        order.verify(cipher).encrypt(any(), anyString());
        order.verify(factors).findForUpdate(userId, MfaFactorStatus.PENDING);
        order.verify(factors).flush();
        assertThat(pending.getStatus()).isEqualTo(MfaFactorStatus.REVOKED);
        assertThat(pending.getVerifiedAt()).isNull();
        when(factors.findForUpdate(userId, MfaFactorStatus.PENDING)).thenReturn(List.of());
        when(factors.countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE)).thenReturn(1L);
        assertThat(service.startTotpEnrollment(userId, "Synthetic second", true).factorId()).isNotNull();
    }

    @Test void initialVerificationLocksAccountBeforeFactorAndRecoveryMutation() {
        UUID factorId = UUID.randomUUID();
        MfaTotpFactor factor = mock(MfaTotpFactor.class);
        when(factors.findByIdAndUserId(factorId, userId)).thenReturn(Optional.of(factor));
        when(factor.canVerifyEnrollmentAt(any(), any())).thenReturn(true);
        when(cipher.decrypt(any(), any())).thenReturn(new byte[20]);
        when(totp.matchingTimeStep(any(), eq("000000"), any())).thenReturn(OptionalLong.of(100));
        when(tokens.hash(anyString())).thenAnswer(call -> call.getArgument(0));
        var priorRecovery = MfaRecoveryCode.create(userId, "synthetic-recovery-hash", java.time.Instant.now(clock));
        when(recovery.findAvailableForUpdate(userId)).thenReturn(List.of(priorRecovery));
        assertThat(service.verifyTotpEnrollment(userId, factorId, "000000", false).recoveryCodes()).hasSize(10);
        var order = inOrder(users, factors, factor, recovery);
        order.verify(users).findByIdForUpdate(userId);
        order.verify(factors).countByUserIdAndStatus(userId, MfaFactorStatus.ACTIVE);
        order.verify(factors).findByIdAndUserId(factorId, userId);
        order.verify(factor).activate(any(), eq(100L));
        order.verify(recovery).findAvailableForUpdate(userId);
        assertThatThrownBy(() -> priorRecovery.use(java.time.Instant.now(clock))).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingAccountNeverMutatesFactorsAndStatusDoesNotInventSessionAssurance() {
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.startTotpEnrollment(userId, "Synthetic", false)).isInstanceOf(ApiException.class);
        verifyNoInteractions(factors, recovery, totp, cipher);
        assertThat(service.status(userId, false).sessionVerified()).isFalse();
    }

    @Test void loginChallengeLocksAccountBeforeChallengeAndFactorOrRecovery() {
        var challenge = MfaLoginChallenge.create(userId, "synthetic-hash", false, null,
                java.time.Instant.now(clock), java.time.Duration.ofMinutes(5));
        when(tokens.hash("synthetic-token")).thenReturn("synthetic-hash");
        when(challenges.findOwnerByTokenHash("synthetic-hash")).thenReturn(Optional.of(userId));
        when(challenges.findByTokenHash("synthetic-hash")).thenReturn(Optional.of(challenge));
        when(tokens.hash("SYNTHETICRECOVERYCODE")).thenReturn("synthetic-code-hash");
        var code = MfaRecoveryCode.create(userId, "synthetic-code-hash", java.time.Instant.now(clock));
        when(recovery.findByUserIdAndCodeHashAndUsedAtIsNullAndRevokedAtIsNull(userId, "synthetic-code-hash"))
                .thenReturn(Optional.of(code));
        assertThat(service.verifyLoginChallenge("synthetic-token", "SYNTHETICRECOVERYCODE").userId()).isEqualTo(userId);
        var order = inOrder(challenges, users, recovery);
        order.verify(challenges).findOwnerByTokenHash("synthetic-hash");
        order.verify(users).findByIdForUpdate(userId);
        order.verify(challenges).findByTokenHash("synthetic-hash");
        order.verify(recovery).findByUserIdAndCodeHashAndUsedAtIsNullAndRevokedAtIsNull(userId, "synthetic-code-hash");
        assertThat(challenge.isUsableAt(java.time.Instant.now(clock))).isFalse();
    }

    @Test void expiredChallengeIsRecheckedAfterAccountLockWithoutFactorAccess() {
        var challenge = MfaLoginChallenge.create(userId, "synthetic-hash", false, null,
                java.time.Instant.now(clock).minusSeconds(300), java.time.Duration.ofSeconds(30));
        when(tokens.hash("synthetic-token")).thenReturn("synthetic-hash");
        when(challenges.findOwnerByTokenHash("synthetic-hash")).thenReturn(Optional.of(userId));
        when(challenges.findByTokenHash("synthetic-hash")).thenReturn(Optional.of(challenge));
        assertThatThrownBy(() -> service.verifyLoginChallenge("synthetic-token", "000000")).isInstanceOf(ApiException.class);
        verify(users).findByIdForUpdate(userId);
        verifyNoInteractions(factors, recovery, totp, cipher);
    }

    @Test void newChallengeCountsOnlyLiveSameUserChallengesWithoutDeletingHistory() {
        when(security.mfaChallengeTtl()).thenReturn(java.time.Duration.ofMinutes(5));
        when(tokens.newRawToken()).thenReturn("synthetic-token");
        when(tokens.hash("synthetic-token")).thenReturn("synthetic-hash");
        assertThat(service.beginLogin(userId, false, null).rawToken()).isEqualTo("synthetic-token");
        var order = inOrder(users, challenges);
        order.verify(users).findByIdForUpdate(userId);
        order.verify(challenges).countByUserIdAndConsumedAtIsNullAndExpiresAtAfter(eq(userId), any());
        order.verify(challenges).save(any());
    }

    @Test void encryptionUnavailableDoesNotSupersedeExistingPendingSetup() {
        when(totp.generateSecret()).thenReturn(new TotpService.GeneratedSecret(new byte[20], "SYNTHETIC"));
        when(cipher.encrypt(any(), anyString())).thenThrow(new ApiException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "SENSITIVE_ENCRYPTION_UNAVAILABLE", "Synthetic unavailable key"));
        assertThatThrownBy(() -> service.startTotpEnrollment(userId, "Synthetic", false)).isInstanceOf(ApiException.class);
        verify(factors, never()).findForUpdate(any(), any());
        verify(factors, never()).flush();
        verify(factors, never()).save(any());
        verifyNoInteractions(recovery, audit);
    }
}
