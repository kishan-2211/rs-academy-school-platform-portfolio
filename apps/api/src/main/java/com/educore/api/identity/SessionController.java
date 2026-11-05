package com.educore.api.identity;

import com.educore.api.config.ApplicationProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final SessionCookieFactory cookieFactory;
    private final ApplicationProperties properties;

    public SessionController(
            SessionService sessionService,
            SessionCookieFactory cookieFactory,
            ApplicationProperties properties) {
        this.sessionService = sessionService;
        this.cookieFactory = cookieFactory;
        this.properties = properties;
    }

    @PostMapping
    ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest) {
        LoginResult result = sessionService.login(request, servletRequest.getHeader("User-Agent"));
        if (!result.isAuthenticated()) {
            return ResponseEntity.ok(LoginResponse.mfaRequired(
                    result.rawChallengeToken(), result.challengeExpiresAt()));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.create(result.rawSessionToken(), request.remember()).toString())
                .body(LoginResponse.authenticated(result.session()));
    }

    @GetMapping("/current")
    AuthSessionResponse current(
            @AuthenticationPrincipal AuthenticatedUser user,
            HttpServletRequest request,
            CsrfToken csrfToken) {
        csrfToken.getToken();
        SessionService.AuthenticatedSession authenticated =
                (SessionService.AuthenticatedSession) request.getAttribute(
                        SessionService.AuthenticatedSession.class.getName());
        return AuthSessionResponse.from(user, authenticated.expiresAt());
    }

    @DeleteMapping("/current")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        findSessionCookie(request).ifPresent(sessionService::logout);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.clear().toString())
                .build();
    }

    @GetMapping
    List<SessionSummaryResponse> sessions(@AuthenticationPrincipal AuthenticatedUser user) {
        return sessionService.listSessions(user);
    }

    @DeleteMapping("/{sessionId}")
    ResponseEntity<Void> revokeSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID sessionId) {
        sessionService.revokeOwnedSession(user, sessionId);
        return ResponseEntity.noContent().build();
    }

    private java.util.Optional<String> findSessionCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return java.util.Optional.empty();
        return Arrays.stream(cookies)
                .filter(cookie -> properties.security().sessionCookieName().equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst();
    }
}
