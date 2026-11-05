package com.educore.api.identity;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.common.api.GatewayWebhookRequestPath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import com.educore.api.tenancy.TenantRequestContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private final SessionService sessionService;
    private final ApplicationProperties properties;
    private final PrivilegedMfaPolicy mfaPolicy;
    private final ApiSecurityErrorWriter errors;

    public SessionAuthenticationFilter(SessionService sessionService, ApplicationProperties properties,
            PrivilegedMfaPolicy mfaPolicy, ApiSecurityErrorWriter errors) {
        this.sessionService = sessionService;
        this.properties = properties;
        this.mfaPolicy = mfaPolicy;
        this.errors = errors;
    }

    // Streaming downloads resume through ASYNC/ERROR dispatches on another thread.
    // Revalidate the HttpOnly session there; never permit a dispatcher type wholesale.
    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        doFilterInternal(request, response, filterChain);
    }

    @Override
    @SuppressWarnings("try")
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (GatewayWebhookRequestPath.matches(request)) {
            // A provider callback authenticates exact raw bytes against its
            // server-owned route, never an incidental browser cookie/principal.
            // Do not replace an ambient tenant/transaction: intake rejects it.
            SecurityContextHolder.clearContext();
            request.removeAttribute(SessionService.AuthenticatedSession.class.getName());
            filterChain.doFilter(request, response);
            return;
        }
        SessionService.AuthenticatedSession authenticatedSession = null;
        if (request.getDispatcherType() == DispatcherType.ASYNC || request.getDispatcherType() == DispatcherType.ERROR) {
            // A captured principal may now be revoked/expired. Retain no stale fallback.
            SecurityContextHolder.clearContext();
            request.removeAttribute(SessionService.AuthenticatedSession.class.getName());
        }
        var prior = SecurityContextHolder.getContext().getAuthentication();
        if (prior == null || prior.getPrincipal() instanceof AuthenticatedUser) {
            // An EduCore principal is never a substitute for this request's live cookie/session.
            SecurityContextHolder.clearContext();
            request.removeAttribute(SessionService.AuthenticatedSession.class.getName());
            authenticatedSession = findSessionCookie(request).flatMap(sessionService::authenticate).orElse(null);
            if (authenticatedSession != null) {
                SessionService.AuthenticatedSession authenticated = authenticatedSession;
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        authenticated.user(),
                        null,
                        authenticated.user().capabilities().stream()
                                .map(capability -> new SimpleGrantedAuthority("CAPABILITY_" + capability))
                                .collect(Collectors.toUnmodifiableSet()));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute(SessionService.AuthenticatedSession.class.getName(), authenticated);
            }
        }
        if (authenticatedSession == null) {
            filterChain.doFilter(request, response);
            return;
        }
        AuthenticatedUser user = authenticatedSession.user();
        try (TenantRequestContext.Scope ignored = TenantRequestContext.open(
                user.userId(), user.activeOrganisationId())) {
            if (mfaPolicy.isRequired(user) && !PrivilegedMfaPolicy.allowsWithoutVerification(request.getMethod(), request.getRequestURI())) {
                mfaPolicy.recordDenied(user);
                errors.mfaRequired(request, response);
                return;
            }
            filterChain.doFilter(request, response);
        }
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
