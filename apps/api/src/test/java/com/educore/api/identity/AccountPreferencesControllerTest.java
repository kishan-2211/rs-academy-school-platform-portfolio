package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.config.SecurityConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

class AccountPreferencesControllerTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private AccountPreferencesService service;
    private final AuthenticatedUser actor = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(),
            "synthetic", null, "Synthetic", false, UUID.randomUUID(), Set.of(), Set.of(), List.of());

    private org.springframework.test.web.servlet.request.RequestPostProcessor signedIn() {
        return authentication(new UsernamePasswordAuthenticationToken(actor, null, List.of()));
    }

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        service = context.getBean(AccountPreferencesService.class);
        when(service.read(any())).thenReturn(new AccountPreferencesService.Preferences(null));
        when(service.update(any(), eq("hi"))).thenReturn(new AccountPreferencesService.Preferences("hi"));
    }
    @AfterEach void close() { context.close(); }

    @Test void authenticatedReadAndWriteUseOnlyTheCurrentActor() throws Exception {
        Cookie csrf = mvc.perform(get("/api/v1/me/preferences").with(signedIn()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getCookie("educore_csrf");
        assertThat(csrf).isNotNull();
        mvc.perform(put("/api/v1/me/preferences").with(signedIn()).cookie(csrf)
                .header("X-CSRF-TOKEN", csrf.getValue()).contentType("application/json")
                .content("{\"preferredLanguage\":\"hi\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preferredLanguage").value("hi"));
        verify(service).update(eq(actor), eq("hi"));
    }
    @Test void rejectsMissingSessionMissingCsrfAndInvalidLanguages() throws Exception {
        mvc.perform(get("/api/v1/me/preferences")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/me/preferences").with(signedIn()).contentType("application/json")
                .content("{\"preferredLanguage\":\"hi\"}")).andExpect(status().isForbidden());
        Cookie csrf = mvc.perform(get("/api/v1/me/preferences").with(signedIn())).andReturn().getResponse().getCookie("educore_csrf");
        assertThat(csrf).isNotNull();
        for (String body : List.of("{}", "{\"preferredLanguage\":null}", "{\"preferredLanguage\":\"fr\"}")) {
            mvc.perform(put("/api/v1/me/preferences").with(signedIn()).cookie(csrf)
                    .header("X-CSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/v1/me/preferences").cookie(csrf).header("X-CSRF-TOKEN", csrf.getValue())
                .contentType("application/json").content("{\"preferredLanguage\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        verify(service, never()).update(any(), any());
    }
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfiguration.class)
    static class Config {
        @Bean AccountPreferencesService service() { return mock(AccountPreferencesService.class); }
        @Bean AccountPreferencesController controller(AccountPreferencesService service) { return new AccountPreferencesController(service); }
        @Bean ApiSecurityErrorWriter errors() { return new ApiSecurityErrorWriter(new ObjectMapper(), Clock.systemUTC()); }
        @Bean com.educore.api.config.ApplicationProperties properties() {
            var properties = mock(com.educore.api.config.ApplicationProperties.class);
            when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));
            return properties;
        }
        @Bean SessionAuthenticationFilter filter() throws Exception {
            var filter = mock(SessionAuthenticationFilter.class);
            doAnswer(invocation -> {
                ((FilterChain) invocation.getArgument(2)).doFilter(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(filter).doFilter(any(), any(), any());
            return filter;
        }
    }
}
