package com.educore.api.identity;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.AccessContextService;
import com.educore.api.tenancy.AccessContextService.AccessContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {

    private static final String LOGIN_ERROR = "The sign-in ID or password is incorrect.";
    private final UserAccountRepository userAccounts;
    private final UserSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokenCodec tokenCodec;
    private final MfaService mfaService;
    private final AccessContextService accessContexts;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final ApplicationProperties properties;
    private final String dummyPasswordHash;

    public SessionService(
            UserAccountRepository userAccounts,
            UserSessionRepository sessions,
            PasswordEncoder passwordEncoder,
            SessionTokenCodec tokenCodec,
            MfaService mfaService,
            AccessContextService accessContexts,
            SecurityAuditService audit,
            Clock clock,
            ApplicationProperties properties) {
        this.userAccounts = userAccounts;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.tokenCodec = tokenCodec;
        this.mfaService = mfaService;
        this.accessContexts = accessContexts;
        this.audit = audit;
        this.clock = clock;
        this.properties = properties;
        this.dummyPasswordHash = passwordEncoder.encode(tokenCodec.newRawToken());
    }

    @Transactional(noRollbackFor = ApiException.class)
    LoginResult login(LoginRequest request, String userAgent) {
        Instant now = Instant.now(clock);
        Optional<UserAccount> candidate = userAccounts.findByLoginIdentifierNormalized(
                UserAccount.normalizeLoginIdentifier(request.identifier()));
        String passwordHash = candidate.map(UserAccount::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), passwordHash);

        if (candidate.isEmpty() || !passwordMatches || !candidate.get().canAuthenticateAt(now)) {
            candidate.filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .ifPresent(user -> user.recordFailedLogin(now));
            audit.record(
                    candidate.map(UserAccount::getId).orElse(null),
                    "identity.password.denied",
                    AuditOutcome.DENIED,
                    "Invalid credentials or unavailable account",
                    Map.of("candidateFound", candidate.isPresent()));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", LOGIN_ERROR);
        }

        UserAccount user = candidate.get();
        if (mfaService.isEnabled(user.getId())) {
            user.recordSuccessfulPassword();
            MfaService.MfaChallengeStart challenge = mfaService.beginLogin(user.getId(), request.remember(), userAgent);
            return LoginResult.mfaRequired(challenge.rawToken(), challenge.expiresAt());
        }

        return issueSession(user, request.remember(), userAgent, false, now);
    }

    @Transactional
    LoginResult completeMfaLogin(MfaService.MfaLoginCompletion completion) {
        Instant now = Instant.now(clock);
        UserAccount user = userAccounts.findByIdForUpdate(completion.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", LOGIN_ERROR));
        if (!user.canAuthenticateAt(now)) {
            audit.record(user.getId(), "identity.session.denied", AuditOutcome.DENIED, "Account unavailable after MFA", Map.of());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "ACCOUNT_UNAVAILABLE", LOGIN_ERROR);
        }
        return issueSession(user, completion.remember(), completion.userAgent(), true, now);
    }

    @Transactional
    Optional<AuthenticatedSession> authenticate(String rawToken) {
        if (rawToken == null || rawToken.length() < 32 || rawToken.length() > 128) return Optional.empty();
        Instant now = Instant.now(clock);
        Optional<UserSession> found = sessions.findByTokenHash(tokenCodec.hash(rawToken));
        if (found.isEmpty()) return Optional.empty();

        UserSession session = found.get();
        if (!session.isValidAt(now)) {
            session.revoke(now);
            return Optional.empty();
        }
        session.touch(now, properties.security().idleTimeout());
        UserAccount user = session.getUser();
        AccessContext accessContext = accessContexts.resolve(user.getId(), session.getActiveOrganisationId());
        if (!java.util.Objects.equals(session.getActiveOrganisationId(), accessContext.activeOrganisationId())) {
            session.selectOrganisation(accessContext.activeOrganisationId());
        }
        return Optional.of(new AuthenticatedSession(
                new AuthenticatedUser(
                        user.getId(),
                        session.getId(),
                        user.getLoginIdentifierNormalized(),
                        user.getEmailNormalized(),
                        user.getDisplayName(),
                        session.isMfaVerified(),
                        accessContext.activeOrganisationId(),
                        accessContext.membershipIds(),
                        accessContext.capabilities(),
                        accessContext.organisations()),
                session.getExpiresAt()));
    }

    @Transactional
    void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        sessions.findByTokenHash(tokenCodec.hash(rawToken)).ifPresent(session -> {
            session.revoke(Instant.now(clock));
            audit.record(
                    session.getUser().getId(),
                    "identity.session.revoked",
                    AuditOutcome.SUCCEEDED,
                    "Current session logout",
                    Map.of("sessionId", session.getId()));
        });
    }

    @Transactional(readOnly = true)
    List<SessionSummaryResponse> listSessions(AuthenticatedUser user) {
        return sessions.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(user.userId()).stream()
                .map(session -> SessionSummaryResponse.from(session, session.getId().equals(user.sessionId())))
                .toList();
    }

    @Transactional
    void revokeOwnedSession(AuthenticatedUser user, UUID sessionId) {
        if (sessionId.equals(user.sessionId())) {
            throw new ApiException(HttpStatus.CONFLICT, "CURRENT_SESSION_REQUIRES_LOGOUT", "Use sign out to revoke the current session.");
        }
        UserSession session = sessions.findByIdAndUserId(sessionId, user.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "The session was not found."));
        session.revoke(Instant.now(clock));
        audit.record(
                user.userId(),
                "identity.session.revoked",
                AuditOutcome.SUCCEEDED,
                "User revoked another session",
                Map.of("sessionId", sessionId));
    }

    @Transactional
    public AuthenticatedUser selectOrganisation(AuthenticatedUser user, UUID organisationId) {
        AccessContext accessContext = accessContexts.select(user.userId(), organisationId);
        UserSession session = sessions.findByIdAndUserId(user.sessionId(), user.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_NOT_FOUND", "The session is no longer available."));
        session.selectOrganisation(organisationId);
        audit.record(
                user.userId(),
                "identity.organisation.selected",
                AuditOutcome.SUCCEEDED,
                null,
                Map.of("organisationId", organisationId, "sessionId", session.getId()));
        return new AuthenticatedUser(
                user.userId(),
                user.sessionId(),
                user.loginIdentifier(),
                user.email(),
                user.displayName(),
                user.mfaVerified(),
                organisationId,
                accessContext.membershipIds(),
                accessContext.capabilities(),
                accessContext.organisations());
    }

    private LoginResult issueSession(
            UserAccount user,
            boolean remember,
            String userAgent,
            boolean mfaVerified,
            Instant now) {
        user.recordSuccessfulLogin(now);
        String rawToken = tokenCodec.newRawToken();
        Duration absoluteTtl = remember
                ? properties.security().rememberedSessionTtl()
                : properties.security().sessionTtl();
        UserSession session = UserSession.create(
                user,
                tokenCodec.hash(rawToken),
                now,
                absoluteTtl,
                properties.security().idleTimeout(),
                userAgent,
                mfaVerified);
        sessions.save(session);
        audit.record(
                user.getId(),
                "identity.session.created",
                AuditOutcome.SUCCEEDED,
                null,
                Map.of("sessionId", session.getId(), "mfaVerified", mfaVerified, "remembered", remember));
        return LoginResult.authenticated(rawToken, session);
    }

    record AuthenticatedSession(AuthenticatedUser user, Instant expiresAt) {
    }
}
