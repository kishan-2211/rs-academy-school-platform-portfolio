package com.educore.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.identity.SessionAuthenticationFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Exercises the real production filter configuration, not the csrf() test bypass. */
class BrowserCsrfSecurityTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfiguration.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void close() { context.close(); }

    private Cookie token() throws Exception {
        Cookie cookie = mvc.perform(get("/api/v1/csrf-test").with(user("synthetic")))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("educore_csrf");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    @Test
    void browserCookieAndRawHeaderPermitAuthorizedWrite() throws Exception {
        Cookie cookie = token();
        mvc.perform(post("/api/v1/csrf-test").with(user("synthetic")
                        .authorities(new SimpleGrantedAuthority("CAPABILITY_attendance.record")))
                        .cookie(cookie).header("X-CSRF-TOKEN", cookie.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void missingMismatchedAndCookieOnlyTokensRemainForbidden() throws Exception {
        Cookie cookie = token();
        var authorized = user("synthetic").authorities(new SimpleGrantedAuthority("CAPABILITY_attendance.record"));
        mvc.perform(post("/api/v1/csrf-test").with(authorized))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/csrf-test").with(authorized).cookie(cookie))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/csrf-test").with(authorized).cookie(cookie)
                        .header("X-CSRF-TOKEN", "not-the-cookie-token"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/csrf-test").with(authorized)
                        .header("X-CSRF-TOKEN", cookie.getValue()))
                .andExpect(status().isForbidden());
    }

    @Test
    void validCsrfNeverGrantsAuthenticationOrCapability() throws Exception {
        Cookie cookie = token();
        mvc.perform(post("/api/v1/csrf-test").cookie(cookie)
                        .header("X-CSRF-TOKEN", cookie.getValue()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/csrf-test").with(user("synthetic-parent")).cookie(cookie)
                        .header("X-CSRF-TOKEN", cookie.getValue()))
                .andExpect(status().isForbidden());
    }

    @Test
    void untrustedOriginsRemainRejected() throws Exception {
        Cookie cookie = token();
        mvc.perform(post("/api/v1/csrf-test").with(user("synthetic")
                        .authorities(new SimpleGrantedAuthority("CAPABILITY_attendance.record")))
                        .cookie(cookie).header("X-CSRF-TOKEN", cookie.getValue())
                        .header("Origin", "https://untrusted.example.test"))
                .andExpect(status().isForbidden());
    }

    @Test
    void maskedFormTokensRemainSupportedWithoutAcceptingRawFormTokens() throws Exception {
        var result = mvc.perform(get("/api/v1/csrf-test").with(user("synthetic")))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("educore_csrf");
        assertThat(cookie).isNotNull();
        CsrfToken masked = (CsrfToken) result.getRequest().getAttribute(CsrfToken.class.getName());
        assertThat(masked.getToken()).isNotEqualTo(cookie.getValue());
        mvc.perform(post("/api/v1/csrf-test").with(user("synthetic")
                        .authorities(new SimpleGrantedAuthority("CAPABILITY_attendance.record")))
                        .cookie(cookie).param("_csrf", masked.getToken()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/csrf-test").with(user("synthetic")
                        .authorities(new SimpleGrantedAuthority("CAPABILITY_attendance.record")))
                        .cookie(cookie).param("_csrf", cookie.getValue()))
                .andExpect(status().isForbidden());
    }

    @org.springframework.boot.test.context.TestConfiguration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfiguration.class)
    static class TestConfiguration {
        @Bean ApplicationProperties properties() {
            ApplicationProperties properties = mock(ApplicationProperties.class);
            when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));
            return properties;
        }
        @Bean SessionAuthenticationFilter sessions() throws Exception {
            SessionAuthenticationFilter filter = mock(SessionAuthenticationFilter.class);
            doAnswer(call -> {
                ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1));
                return null;
            }).when(filter).doFilter(any(), any(), any());
            return filter;
        }
        @Bean ApiSecurityErrorWriter errors() {
            return new ApiSecurityErrorWriter(new ObjectMapper(), Clock.systemUTC());
        }
        @Bean TestEndpoint endpoint() { return new TestEndpoint(); }
    }

    @RestController
    static class TestEndpoint {
        @GetMapping("/api/v1/csrf-test")
        String read(CsrfToken token) { token.getToken(); return "synthetic"; }
        @PostMapping("/api/v1/csrf-test")
        @PreAuthorize("hasAuthority('CAPABILITY_attendance.record')")
        String write() { return "synthetic"; }
    }
}
