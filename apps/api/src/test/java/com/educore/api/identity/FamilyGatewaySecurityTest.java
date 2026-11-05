package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.common.api.GlobalExceptionHandler;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.SecurityConfiguration;
import com.educore.api.finance.FamilyGatewayPaymentController;
import com.educore.api.finance.FamilyGatewayPaymentModels.*;
import com.educore.api.finance.FamilyGatewayPaymentService;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Real MVC/session/method-security/CSRF/CORS boundaries. Financial/object authority is separately tested against PostgreSQL. */
class FamilyGatewaySecurityTest {
    private static final String PARENT = "synthetic-family-session-token-000000001";
    private static final String READER = "synthetic-family-fee-reader-token-000001";
    private static final String COLLECTOR = "synthetic-family-collector-token-0000001";
    private static final String STUDENT = "synthetic-family-student-token-00000001";
    private static final String STAFF = "synthetic-family-staff-token-0000000001";
    private final UUID organisation = UUID.randomUUID(), learner = UUID.randomUUID(), attempt = UUID.randomUUID(), charge = UUID.randomUUID(), requestId = UUID.randomUUID();
    private final AuthenticatedUser parent = actor("Synthetic guardian", "portal.guardian");
    private final AuthenticatedUser reader = actor("Synthetic fee reader", "fees.read");
    private final AuthenticatedUser collector = actor("Synthetic collector", "fees.collect");
    private final AuthenticatedUser student = actor("Synthetic student", "portal.student");
    private final AuthenticatedUser staff = actor("Synthetic staff", "portal.staff");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FamilyGatewayPaymentService payments;
    private SessionService sessions;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        payments = context.getBean(FamilyGatewayPaymentService.class); sessions = context.getBean(SessionService.class);
        when(sessions.authenticate(PARENT)).thenReturn(session(parent)); when(sessions.authenticate(READER)).thenReturn(session(reader));
        when(sessions.authenticate(COLLECTOR)).thenReturn(session(collector)); when(sessions.authenticate(STUDENT)).thenReturn(session(student));
        when(sessions.authenticate(STAFF)).thenReturn(session(staff));
        when(payments.history(any(), any(), anyInt())).thenAnswer(call -> {
            assertActor(call.getArgument(0)); return new History(call.getArgument(1), Availability.DISABLED, call.getArgument(2), 20, false, List.of());
        });
        when(payments.payment(any(), any())).thenAnswer(call -> { assertActor(call.getArgument(0)); return payment(); });
        when(payments.initiate(any(), any(), any())).thenAnswer(call -> { assertActor(call.getArgument(0)); return payment(); });
        when(payments.continueCheckout(any(), any(), any())).thenAnswer(call -> {
            assertActor(call.getArgument(0)); return new Continuation(attempt, URI.create("https://payments-test.cashfree.com/links/synthetic_authorised_checkout"),
                    Instant.parse("2026-10-15T06:30:00Z"));
        });
    }

    @AfterEach void close() {
        assertThat(TenantRequestContext.current()).isEmpty(); SecurityContextHolder.clearContext(); if (context != null) context.close();
    }

    @Test void anonymousAndRevokedSessionsCannotReadRealPaymentRoutes() throws Exception {
        mvc.perform(get(historyPath())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get(paymentPath()).cookie(cookie("synthetic-revoked-family-session-000001"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(payments);
    }

    @Test void actualGuardianSessionBindsTheTenantAndMinimalPrivateHistoryAndExactStatus() throws Exception {
        mvc.perform(get(historyPath()).cookie(cookie(PARENT)).param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.learnerId").value(learner.toString())).andExpect(jsonPath("$.page").value(2))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("private"), org.hamcrest.Matchers.containsString("no-store"))))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
        mvc.perform(get(paymentPath()).cookie(cookie(PARENT))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(attempt.toString()))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.checkoutUrl").doesNotExist())
                .andExpect(jsonPath("$.payerPhone").doesNotExist()).andExpect(jsonPath("$.routeKey").doesNotExist());
        verify(payments).history(parent, learner, 2); verify(payments).payment(parent, attempt); verify(sessions, atLeastOnce()).authenticate(PARENT);
    }

    @Test void validCookieHeaderCsrfStartsOnlyTheExactStableValidatedGuardianIntent() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post(historyPath()).cookie(cookie(PARENT), csrf).header("X-CSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(attempt.toString())).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.checkoutUrl").doesNotExist());
        verify(payments).initiate(parent, learner, new InitiateRequest(requestId, List.of(new ChargeSelection(charge, new BigDecimal("750.00"))), "0000000000"));
        verify(payments, never()).continueCheckout(any(), any(), any());
    }

    @Test void explicitContinuationUsesRealCsrfAndBrowserSuccessParametersNeverConfirmPayment() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post(continuePath()).param("paid", "true").param("success", "true").cookie(cookie(PARENT), csrf).header("X-CSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.attemptId").value(attempt.toString()))
                .andExpect(jsonPath("$.checkoutUrl").value("https://payments-test.cashfree.com/links/synthetic_authorised_checkout"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get(paymentPath()).param("paid", "true").cookie(cookie(PARENT))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        verify(payments).continueCheckout(parent, learner, attempt); verify(payments).payment(parent, attempt); verify(payments, never()).initiate(any(), any(), any());
    }

    @Test void missingCookieOnlyHeaderOnlyMismatchedCsrfAndMissingSessionCannotInvokeMutations() throws Exception {
        Cookie csrf = csrfCookie();
        for (String path : List.of(historyPath(), continuePath())) {
            mvc.perform(post(path).cookie(cookie(PARENT)).contentType("application/json").content(body())).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(PARENT), csrf).contentType("application/json").content(body())).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(PARENT)).header("X-CSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body())).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(PARENT), csrf).header("X-CSRF-TOKEN", "synthetic-mismatched-token").contentType("application/json").content(body())).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(csrf).header("X-CSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body())).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(payments);
    }

    @Test void feeReadCollectStudentAndStaffCapabilitiesCannotReplaceGuardianAuthority() throws Exception {
        Cookie csrf = csrfCookie();
        for (String token : List.of(READER, COLLECTOR, STUDENT, STAFF)) {
            mvc.perform(get(historyPath()).cookie(cookie(token))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
            mvc.perform(get(paymentPath()).cookie(cookie(token))).andExpect(status().isForbidden());
            String response = mvc.perform(post(historyPath()).cookie(cookie(token), csrf).header("X-CSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body()))
                    .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(token, parent.userId().toString(), "AuthorizationDeniedException", "portal.guardian");
            mvc.perform(post(continuePath()).cookie(cookie(token), csrf).header("X-CSRF-TOKEN", csrf.getValue())).andExpect(status().isForbidden());
        }
        verifyNoInteractions(payments);
    }

    @Test void invalidUuidPageAndFinancialTermsAreRejectedBeforeServiceWithoutEchoingSubmittedValues() throws Exception {
        Cookie csrf = csrfCookie();
        String invalidPath = "/api/v1/family/fee-payments/synthetic-private-invalid";
        // The shared Problem Detail contract intentionally identifies the
        // requested URI in instance. Sanitisation applies to diagnostics and
        // rejected query/body values, not removal of that existing path field.
        String invalidUuid = mvc.perform(get(invalidPath).cookie(cookie(PARENT)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("One or more values are invalid."))
                .andExpect(jsonPath("$.violations").isEmpty()).andExpect(jsonPath("$.details").isEmpty())
                .andExpect(jsonPath("$.instance").value(invalidPath)).andReturn().getResponse().getContentAsString();
        assertThat(invalidUuid).doesNotContain(PARENT, parent.userId().toString(), "MethodArgumentTypeMismatchException");
        for (var invalid : List.of(get(historyPath()).param("page", "-1"), get(historyPath()).param("page", "10001"),
                get(historyPath()).param("page", "synthetic-private-invalid"))) {
            String response = mvc.perform(invalid.cookie(cookie(PARENT))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request")).andExpect(jsonPath("$.detail").value("One or more values are invalid."))
                    .andExpect(jsonPath("$.violations").isEmpty()).andExpect(jsonPath("$.details").isEmpty())
                    .andExpect(jsonPath("$.instance").value(historyPath())).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("synthetic-private-invalid", PARENT, parent.userId().toString());
        }
        String single = "{\"chargeId\":\"" + charge + "\",\"amount\":750.00}";
        for (String invalid : List.of("{}", "null", body().replace("750.00", "1.001"), body().replace("750.00", "-1"), body().replace("750.00", "100000000.01"),
                body().replace("0000000000", "not-a-phone"), body().replace("[" + single + "]", "[]"),
                body().replace("[" + single + "]", "[" + single + "," + single + "]"), body().replace("750.00", "null"))) {
            String response = mvc.perform(post(historyPath()).cookie(cookie(PARENT), csrf).header("X-CSRF-TOKEN", csrf.getValue()).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("not-a-phone", PARENT, parent.userId().toString(), "750.00");
        }
        verifyNoInteractions(payments);
    }

    @Test void untrustedOriginCannotReadOrMutateEvenWithRealSessionAndCsrf() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(get(historyPath()).cookie(cookie(PARENT)).header("Origin", "https://untrusted.example.invalid")).andExpect(status().isForbidden());
        mvc.perform(post(historyPath()).cookie(cookie(PARENT), csrf).header("X-CSRF-TOKEN", csrf.getValue()).header("Origin", "https://untrusted.example.invalid")
                .contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(continuePath()).cookie(cookie(PARENT), csrf).header("X-CSRF-TOKEN", csrf.getValue()).header("Origin", "https://untrusted.example.invalid"))
                .andExpect(status().isForbidden()); verifyNoInteractions(payments);
    }

    @Test void scopeConflictAndUnexpectedFailuresUseRealAdviceWithoutPrivateDiagnostics() throws Exception {
        Cookie csrf = csrfCookie();
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "FAMILY_PAYMENT_NOT_FOUND", "This payment is unavailable through your active family relationship."))
                .when(payments).payment(parent, attempt);
        String missing = mvc.perform(get(paymentPath()).cookie(cookie(PARENT))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FAMILY_PAYMENT_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        doThrow(new ApiException(HttpStatus.CONFLICT, "FAMILY_CHECKOUT_UNAVAILABLE", "Refresh the verified payment status before paying again."))
                .when(payments).continueCheckout(parent, learner, attempt);
        String conflict = mvc.perform(post(continuePath()).cookie(cookie(PARENT), csrf).header("X-CSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAMILY_CHECKOUT_UNAVAILABLE")).andReturn().getResponse().getContentAsString();
        doThrow(new IllegalStateException("Synthetic private payer/route/cipher diagnostic")).when(payments).payment(parent, attempt);
        String failed = mvc.perform(get(paymentPath()).cookie(cookie(PARENT))).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        for (String response : List.of(missing, conflict, failed)) assertThat(response).doesNotContain(PARENT, parent.userId().toString(),
                "Synthetic private payer", "IllegalStateException", "cipher", "payments-test.cashfree.com");
    }

    private Cookie csrfCookie() throws Exception {
        Cookie csrf = mvc.perform(get(historyPath()).cookie(cookie(PARENT))).andExpect(status().isOk()).andReturn().getResponse().getCookie("educore_csrf");
        assertThat(csrf).isNotNull(); clearInvocations(payments); return csrf;
    }
    private String historyPath() { return "/api/v1/family/children/" + learner + "/fee-payments"; }
    private String paymentPath() { return "/api/v1/family/fee-payments/" + attempt; }
    private String continuePath() { return historyPath() + "/" + attempt + "/continue"; }
    private String body() { return "{\"clientRequestId\":\"" + requestId + "\",\"allocations\":[{\"chargeId\":\"" + charge + "\",\"amount\":750.00}],\"payerPhone\":\"0000000000\"}"; }
    private Cookie cookie(String token) { return new Cookie("educore_session", token); }
    private Payment payment() {
        var now = Instant.parse("2026-10-15T06:00:00Z"); return new Payment(attempt, learner, UUID.randomUUID(), "Synthetic historical session", new BigDecimal("750.00"), "INR",
                Status.PENDING, now, now.plusSeconds(1800), LocalDate.parse("2026-10-15"), true, true, false, List.of(), null);
    }
    private AuthenticatedUser actor(String name, String capability) {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic-family-security", null, name, true, organisation, Set.of(UUID.randomUUID()), Set.of(capability), List.of());
    }
    private Optional<SessionService.AuthenticatedSession> session(AuthenticatedUser actor) { return Optional.of(new SessionService.AuthenticatedSession(actor, Instant.now().plusSeconds(60))); }
    private void assertActor(AuthenticatedUser actor) {
        assertThat(actor).isEqualTo(parent); var current = TenantRequestContext.current().orElseThrow();
        assertThat(current.userId()).isEqualTo(parent.userId()); assertThat(current.organisationId()).isEqualTo(organisation);
    }

    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config {
        @Bean FamilyGatewayPaymentService payments() { return mock(FamilyGatewayPaymentService.class); }
        @Bean FamilyGatewayPaymentController controller(FamilyGatewayPaymentService payments) { return new FamilyGatewayPaymentController(payments); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean GlobalExceptionHandler advice(Clock clock) { return new GlobalExceptionHandler(clock); }
        @Bean ApiSecurityErrorWriter errors(Clock clock) { return new ApiSecurityErrorWriter(new ObjectMapper(), clock); }
        @Bean SessionService sessions() { return mock(SessionService.class); }
        @Bean PrivilegedMfaPolicy mfa() { return mock(PrivilegedMfaPolicy.class); }
        @Bean ApplicationProperties properties() {
            var properties = mock(ApplicationProperties.class); var security = mock(ApplicationProperties.Security.class);
            when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000")); when(properties.security()).thenReturn(security);
            when(security.sessionCookieName()).thenReturn("educore_session"); return properties;
        }
        @Bean SessionAuthenticationFilter filter(SessionService sessions, ApplicationProperties properties, PrivilegedMfaPolicy mfa, ApiSecurityErrorWriter errors) {
            return new SessionAuthenticationFilter(sessions, properties, mfa, errors);
        }
    }
}
