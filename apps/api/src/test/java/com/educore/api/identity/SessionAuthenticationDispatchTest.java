package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.ObjectMapper;

class SessionAuthenticationDispatchTest {
    private final SessionService sessions = mock(SessionService.class);
    private final ApplicationProperties properties = mock(ApplicationProperties.class);
    private final ApplicationProperties.Security security = mock(ApplicationProperties.Security.class);
    private final PrivilegedMfaPolicy mfaPolicy = mock(PrivilegedMfaPolicy.class);
    private final SessionAuthenticationFilter filter = new SessionAuthenticationFilter(sessions, properties,
            mfaPolicy, new ApiSecurityErrorWriter(new ObjectMapper(), Clock.systemUTC()));
    private final String token = UUID.randomUUID().toString();
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(),
            "synthetic-dispatch@example.test", "synthetic-dispatch@example.test", "Synthetic dispatch account", false,
            UUID.randomUUID(), Set.of(UUID.randomUUID()), Set.of("portal.guardian"), List.of());

    SessionAuthenticationDispatchTest() {
        when(properties.security()).thenReturn(security);
        when(security.sessionCookieName()).thenReturn("educore_session");
        when(sessions.authenticate(token)).thenReturn(Optional.of(new SessionService.AuthenticatedSession(user, Instant.now().plusSeconds(60))));
    }
    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void asyncDispatchReauthenticatesAndScopesTheCurrentTenant() throws Exception { authorized(DispatcherType.ASYNC); }
    @Test void errorDispatchReauthenticatesAndScopesTheCurrentTenant() throws Exception { authorized(DispatcherType.ERROR); }
    @Test void ordinaryRequestsRetainTheExistingSessionBoundary() throws Exception { authorized(DispatcherType.REQUEST); }
    @Test void asyncDispatchNeverReusesACapturedRevokedSession() throws Exception { revoked(DispatcherType.ASYNC); }
    @Test void errorDispatchNeverReusesACapturedRevokedSession() throws Exception { revoked(DispatcherType.ERROR); }

    @Test void asyncFailureAlwaysClearsThreadLocalTenantScope() {
        assertThatThrownBy(() -> filter.doFilter(request(DispatcherType.ASYNC, true), new MockHttpServletResponse(), (req, res) -> {
            assertThat(TenantRequestContext.current()).isPresent();
            throw new java.io.IOException("Synthetic downstream failure");
        })).isInstanceOf(java.io.IOException.class);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void anonymousRedispatchNeverCreatesAuthenticationOrTenantScope() throws Exception {
        for (DispatcherType type : List.of(DispatcherType.ASYNC, DispatcherType.ERROR)) {
            SecurityContextHolder.clearContext();
            filter.doFilter(request(type, false), new MockHttpServletResponse(), (req, res) -> {
                assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
                assertThat(TenantRequestContext.current()).isEmpty();
            });
        }
    }

    @Test void nestedErrorDispatchRevalidatesWithoutLeakingTenantScope() throws Exception {
        var request = request(DispatcherType.ERROR, true);
        request.setAttribute(SessionAuthenticationFilter.class.getName() + ".FILTERED", true);
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(TenantRequestContext.current().orElseThrow().organisationId()).isEqualTo(user.activeOrganisationId());
        });
        verify(sessions).authenticate(token);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void restrictedPasswordSessionsAreDeniedOnEveryDispatchAndClearTenantScope() throws Exception {
        when(mfaPolicy.isRequired(user)).thenReturn(true);
        for (DispatcherType type : List.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR)) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request(type, true), response, (req, res) -> { throw new AssertionError("Business chain must not run"); });
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("MFA_VERIFICATION_REQUIRED").doesNotContain(token);
            assertThat(TenantRequestContext.current()).isEmpty();
        }
    }

    @Test void nestedErrorDispatchCannotBypassPrivilegedMfa() throws Exception {
        when(mfaPolicy.isRequired(user)).thenReturn(true);
        var request = request(DispatcherType.ERROR, true);
        request.setAttribute(SessionAuthenticationFilter.class.getName() + ".FILTERED", true);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> { throw new AssertionError("Nested errors must not bypass MFA"); });
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(TenantRequestContext.current()).isEmpty();
        verify(mfaPolicy).recordDenied(user);
    }

    @Test void exactSelfServiceRemainsAvailableInsideTenantScope() throws Exception {
        when(mfaPolicy.isRequired(user)).thenReturn(true);
        var request = new MockHttpServletRequest("GET", "/api/v1/context");
        request.setCookies(new Cookie("educore_session", token));
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(TenantRequestContext.current().orElseThrow().userId()).isEqualTo(user.userId());
        });
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void ordinaryCapturedEduCorePrincipalAlsoRequiresLiveSession() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        when(sessions.authenticate(token)).thenReturn(Optional.empty());
        filter.doFilter(request(DispatcherType.REQUEST, true), new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(TenantRequestContext.current()).isEmpty();
        });
        verify(sessions).authenticate(token);
    }

    private void authorized(DispatcherType type) throws Exception {
        filter.doFilter(request(type, true), new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(user);
            assertThat(TenantRequestContext.current().orElseThrow().userId()).isEqualTo(user.userId());
            assertThat(TenantRequestContext.current().orElseThrow().organisationId()).isEqualTo(user.activeOrganisationId());
        });
        verify(sessions).authenticate(token);
        assertThat(TenantRequestContext.current()).isEmpty();
    }
    private void revoked(DispatcherType type) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        when(sessions.authenticate(token)).thenReturn(Optional.empty());
        filter.doFilter(request(type, true), new MockHttpServletResponse(), (req, res) -> {
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
            assertThat(TenantRequestContext.current()).isEmpty();
        });
        verify(sessions).authenticate(token);
    }
    private MockHttpServletRequest request(DispatcherType type, boolean cookie) {
        var request = new MockHttpServletRequest("GET", "/api/v1/family/children/synthetic/documents/synthetic/download");
        request.setDispatcherType(type);
        if (type == DispatcherType.ERROR) request.setAttribute(WebUtils.ERROR_REQUEST_URI_ATTRIBUTE, request.getRequestURI());
        if (cookie) request.setCookies(new Cookie("educore_session", token));
        return request;
    }
}
