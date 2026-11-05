package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.common.api.GlobalExceptionHandler;
import com.educore.api.communication.CommunicationInboxService;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.SecurityConfiguration;
import com.educore.api.family.FamilyAttendanceService;
import com.educore.api.family.FamilyController;
import com.educore.api.family.FamilyLearningService;
import com.educore.api.family.FamilyResultService;
import com.educore.api.family.FamilyService;
import com.educore.api.finance.FamilyFeeAccountsResponse;
import com.educore.api.finance.FamilyFeesResponse;
import com.educore.api.finance.FamilyFinanceService;
import com.educore.api.tenancy.TenantRequestContext;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

/** Real GET/MVC/session/method-security boundaries; database relationships are verified separately. */
class FamilyFeeAccountsSecurityTest {
    private static final String PARENT = "synthetic-historical-parent-session-000001";
    private static final String STAFF = "synthetic-historical-staff-session-0000001";
    private static final String ACCOUNTANT = "synthetic-historical-accountant-session-1";
    private static final String STUDENT = "synthetic-historical-student-session-00001";
    private final UUID org = UUID.randomUUID(), learner = UUID.randomUUID(), account = UUID.randomUUID(), session = UUID.randomUUID();
    private final AuthenticatedUser parent = actor("portal.guardian"), staff = actor("portal.staff"),
            accountant = actor("fees.read"), student = actor("portal.student");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FamilyFinanceService finance;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        finance = context.getBean(FamilyFinanceService.class);
        var sessions = context.getBean(SessionService.class);
        when(sessions.authenticate(PARENT)).thenReturn(authenticated(parent));
        when(sessions.authenticate(STAFF)).thenReturn(authenticated(staff));
        when(sessions.authenticate(ACCOUNTANT)).thenReturn(authenticated(accountant));
        when(sessions.authenticate(STUDENT)).thenReturn(authenticated(student));
        when(finance.feeAccounts(any(), any(), anyInt(), anyInt())).thenAnswer(call -> {
            assertActor(call.getArgument(0));
            int page = call.getArgument(2), size = call.getArgument(3);
            return new FamilyFeeAccountsResponse(call.getArgument(1), null, page, size, false,
                    page == 0 ? List.of(new FamilyFeeAccountsResponse.Account(account, session, "Synthetic earlier session",
                            "CLOSED", "INR", new BigDecimal("-25.25"), false)) : List.of());
        });
        when(finance.fees(any(), any(), nullable(UUID.class))).thenAnswer(call -> {
            assertActor(call.getArgument(0));
            return new FamilyFeesResponse(call.getArgument(1), call.getArgument(2), "Synthetic selected session", "INR",
                    "NOT_CONFIGURED", new BigDecimal("1000.00"), new BigDecimal("200.00"), new BigDecimal("800.00"),
                    List.of(), List.of(), List.of());
        });
    }

    @AfterEach void close() {
        assertThat(TenantRequestContext.current()).isEmpty();
        SecurityContextHolder.clearContext(); if (context != null) context.close();
    }

    @Test void anonymousAndRevokedSessionsCannotReadStatementsOrAccountLists() throws Exception {
        for (String path : List.of(accountsPath(), feesPath())) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
            mvc.perform(get(path).cookie(cookie("synthetic-historical-revoked-session-01")))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(finance);
    }

    @Test void guardianListUsesBoundedActualArgumentsAndPrivateExplicitNullJsonUnderNonNullDefaults() throws Exception {
        // Demonstrate the same scoped mapper really excludes ordinary nulls;
        // the DTO annotation must explicitly retain its contract-required null.
        assertThat(Config.JSON.valueToTree(new NullProbe(null)).has("absent")).isFalse();
        var response = mvc.perform(get(accountsPath()).cookie(cookie(PARENT)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.learnerId").value(learner.toString()))
                .andExpect(jsonPath("$.currentAccountId").value(nullValue()))
                .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.accounts[0].id").value(account.toString()))
                .andExpect(jsonPath("$.accounts[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.accounts[0].balance").value(-25.25))
                .andExpect(jsonPath("$.accounts[0].currentSession").value(false)).andReturn().getResponse();
        assertThat(response.getHeaders("Vary")).contains("Cookie");
        var tree = Config.JSON.readTree(response.getContentAsString());
        assertThat(tree.has("currentAccountId")).isTrue(); assertThat(tree.get("currentAccountId").isNull()).isTrue();
        assertThat(tree.size()).isEqualTo(6); assertThat(tree.get("accounts").get(0).size()).isEqualTo(7);
        assertThat(response.getContentAsString()).doesNotContain("closedBy", "closeReason", "evidence", "merchant", "credentials", "refund");
        mvc.perform(get(accountsPath()).cookie(cookie(PARENT)).param("page", "10000").param("pageSize", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(10000))
                .andExpect(jsonPath("$.pageSize").value(50)).andExpect(jsonPath("$.accounts").isEmpty());
        verify(finance).feeAccounts(parent, learner, 0, 20); verify(finance).feeAccounts(parent, learner, 10000, 50);
    }

    @Test void optionalExactAccountIsPassedUnchangedAndOmissionRetainsNullDefaultSelection() throws Exception {
        var exact = mvc.perform(get(feesPath()).cookie(cookie(PARENT)).param("accountId", account.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accountId").value(account.toString()))
                .andExpect(jsonPath("$.balance").value(800.00))
                .andExpect(header().string("Cache-Control", "no-store, private")).andReturn().getResponse();
        assertThat(exact.getHeaders("Vary")).contains("Cookie");
        mvc.perform(get(feesPath()).cookie(cookie(PARENT))).andExpect(status().isOk())
                .andExpect(jsonPath("$.academicSessionName").value("Synthetic selected session"));
        verify(finance).fees(parent, learner, account); verify(finance).fees(parent, learner, null);
        verify(finance, never()).fees(any(), any());
    }

    @Test void invalidPageSizeAndUuidFailBeforeFinanceWithoutEchoingPrivateQueryValues() throws Exception {
        for (String[] invalid : List.of(new String[]{"page", "-1"}, new String[]{"page", "10001"},
                new String[]{"pageSize", "0"}, new String[]{"pageSize", "51"}, new String[]{"page", "synthetic-private-invalid"})) {
            String body = mvc.perform(get(accountsPath()).cookie(cookie(PARENT)).param(invalid[0], invalid[1]))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("One or more values are invalid."))
                    .andExpect(jsonPath("$.violations").isEmpty()).andExpect(jsonPath("$.details").isEmpty())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("synthetic-private-invalid", PARENT, parent.userId().toString(), "HandlerMethodValidationException");
        }
        String badAccount = mvc.perform(get(feesPath()).cookie(cookie(PARENT)).param("accountId", "synthetic-private-account"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(badAccount).doesNotContain("synthetic-private-account", PARENT, "MethodArgumentTypeMismatchException");
        // Existing Problem Detail.instance intentionally retains the requested
        // URI; invalid query diagnostics and values must still remain private.
        for (String suffix : List.of("fees", "fee-accounts")) {
            mvc.perform(get("/api/v1/family/children/synthetic-invalid-learner/" + suffix).cookie(cookie(PARENT)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("One or more values are invalid."));
        }
        verifyNoInteractions(finance);
    }

    @Test void staffAccountantAndStudentCannotSubstituteForGuardianAuthority() throws Exception {
        for (String token : List.of(STAFF, ACCOUNTANT, STUDENT)) {
            for (String path : List.of(accountsPath(), feesPath())) {
                String body = mvc.perform(get(path).cookie(cookie(token))).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("ACCESS_DENIED")).andReturn().getResponse().getContentAsString();
                assertThat(body).doesNotContain(token, parent.userId().toString(), "portal.guardian", "AuthorizationDeniedException");
            }
        }
        verifyNoInteractions(finance);
    }

    @Test void liveServiceRevocationAndForeignAccountDenialsRemainNotFoundWithoutPrivateDiagnostics() throws Exception {
        var missing = new ApiException(HttpStatus.NOT_FOUND, "FAMILY_PAYMENT_NOT_FOUND",
                "This learner or payment is not available through your active family relationship.");
        doThrow(missing).when(finance).feeAccounts(parent, learner, 0, 20);
        doThrow(missing).when(finance).fees(parent, learner, account);
        for (var request : List.of(get(accountsPath()), get(feesPath()).param("accountId", account.toString()))) {
            String body = mvc.perform(request.cookie(cookie(PARENT))).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("FAMILY_PAYMENT_NOT_FOUND"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(PARENT, parent.userId().toString(), account.toString(), "Synthetic earlier session",
                    "closedBy", "credentials", "merchant", "SQLException", "ApiException");
        }
    }

    private String accountsPath() { return "/api/v1/family/children/" + learner + "/fee-accounts"; }
    private String feesPath() { return "/api/v1/family/children/" + learner + "/fees"; }
    private Cookie cookie(String token) { return new Cookie("educore_session", token); }
    private AuthenticatedUser actor(String capability) {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic-historical-family", null,
                "Synthetic family actor", false, org, Set.of(UUID.randomUUID()), Set.of(capability), List.of());
    }
    private Optional<SessionService.AuthenticatedSession> authenticated(AuthenticatedUser actor) {
        return Optional.of(new SessionService.AuthenticatedSession(actor, Instant.now().plusSeconds(60)));
    }
    private void assertActor(AuthenticatedUser actor) {
        assertThat(actor).isEqualTo(parent); var scope = TenantRequestContext.current().orElseThrow();
        assertThat(scope.userId()).isEqualTo(parent.userId()); assertThat(scope.organisationId()).isEqualTo(org);
    }
    private record NullProbe(String absent) {}

    @EnableWebMvc @EnableWebSecurity @Import({SecurityConfiguration.class, FamilyController.class})
    static class Config implements WebMvcConfigurer {
        static final JsonMapper JSON = JsonMapper.builder().changeDefaultPropertyInclusion(
                value -> value.withValueInclusion(JsonInclude.Include.NON_NULL)).build();
        @Override public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
            builder.withJsonConverter(new JacksonJsonHttpMessageConverter(JSON));
        }
        @Bean FamilyService family() { return mock(FamilyService.class); }
        @Bean FamilyAttendanceService attendance() { return mock(FamilyAttendanceService.class); }
        @Bean FamilyResultService results() { return mock(FamilyResultService.class); }
        @Bean FamilyLearningService learning() { return mock(FamilyLearningService.class); }
        @Bean FamilyFinanceService finance() { return mock(FamilyFinanceService.class); }
        @Bean CommunicationInboxService communications() { return mock(CommunicationInboxService.class); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean GlobalExceptionHandler advice(Clock clock) { return new GlobalExceptionHandler(clock); }
        @Bean ApiSecurityErrorWriter errors(Clock clock) { return new ApiSecurityErrorWriter(JSON, clock); }
        @Bean SessionService sessions() { return mock(SessionService.class); }
        @Bean PrivilegedMfaPolicy mfa() { return mock(PrivilegedMfaPolicy.class); }
        @Bean ApplicationProperties properties() {
            var properties = mock(ApplicationProperties.class); var security = mock(ApplicationProperties.Security.class);
            when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000")); when(properties.security()).thenReturn(security);
            when(security.sessionCookieName()).thenReturn("educore_session"); return properties;
        }
        @Bean SessionAuthenticationFilter filter(SessionService sessions, ApplicationProperties properties,
                PrivilegedMfaPolicy mfa, ApiSecurityErrorWriter errors) {
            return new SessionAuthenticationFilter(sessions, properties, mfa, errors);
        }
    }
}
