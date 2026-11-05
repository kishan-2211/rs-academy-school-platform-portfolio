package com.educore.api.identity;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/mfa")
public class MfaController {

    private final MfaService mfaService;
    private final SessionService sessionService;
    private final SessionCookieFactory cookieFactory;

    public MfaController(
            MfaService mfaService,
            SessionService sessionService,
            SessionCookieFactory cookieFactory) {
        this.mfaService = mfaService;
        this.sessionService = sessionService;
        this.cookieFactory = cookieFactory;
    }

    @PostMapping("/challenges/verify")
    ResponseEntity<LoginResponse> verifyLoginChallenge(
            @Valid @RequestBody VerifyMfaChallengeRequest request) {
        MfaService.MfaLoginCompletion completion =
                mfaService.verifyLoginChallenge(request.challengeToken(), request.code());
        LoginResult result = sessionService.completeMfaLogin(completion);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookieFactory.create(result.rawSessionToken(), completion.remember()).toString())
                .body(LoginResponse.authenticated(result.session()));
    }

    @GetMapping
    MfaStatusResponse status(@AuthenticationPrincipal AuthenticatedUser user) {
        return mfaService.status(user.userId(), user.mfaVerified());
    }

    @PostMapping("/totp/enrollments")
    TotpEnrollmentResponse startTotpEnrollment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody StartTotpEnrollmentRequest request) {
        return mfaService.startTotpEnrollment(user.userId(), request.label(), user.mfaVerified());
    }

    @PostMapping("/totp/enrollments/{factorId}/verify")
    RecoveryCodesResponse verifyTotpEnrollment(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID factorId,
            @Valid @RequestBody VerifyMfaCodeRequest request) {
        return mfaService.verifyTotpEnrollment(user.userId(), factorId, request.code(), user.mfaVerified());
    }
}
