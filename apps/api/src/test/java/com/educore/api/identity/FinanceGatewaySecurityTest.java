package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.common.api.GlobalExceptionHandler;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.SecurityConfiguration;
import com.educore.api.finance.FinanceController;
import com.educore.api.finance.FinanceController.GatewayCapturePostingRequest;
import com.educore.api.finance.FinanceService;
import com.educore.api.finance.GatewayCaptureQueueResponse;
import com.educore.api.finance.CollectionRegisterResponse;
import com.educore.api.finance.CollectionRegisterExportResponse;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.http.Cookie;
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
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import tools.jackson.databind.ObjectMapper;

/** Actual finance routes, session filter, advice and production CSRF/CORS rules; no csrf() bypass. */
class FinanceGatewaySecurityTest {
    private static final String QUEUE = "/api/v1/finance/gateway/captures";
    private static final String READER_TOKEN = "synthetic-reader-session-token-0000000001";
    private static final String COLLECTOR_TOKEN = "synthetic-collector-session-token-000001";
    private static final String PARENT_TOKEN = "synthetic-guardian-session-token-0000001";
    private final UUID schoolId = UUID.randomUUID();
    private final UUID organisationId = UUID.randomUUID();
    private final UUID captureId = UUID.randomUUID();
    private final UUID paymentId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2026, 10, 10);
    private final AuthenticatedUser reader = actor("Synthetic reader", "fees.read");
    private final AuthenticatedUser collector = actor("Synthetic collector", "fees.collect");
    private final AuthenticatedUser guardian = actor("Synthetic guardian", "portal.guardian");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private FinanceService finance;
    private SessionService sessions;

    @BeforeEach void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Config.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        finance = context.getBean(FinanceService.class);
        sessions = context.getBean(SessionService.class);
        when(sessions.authenticate(READER_TOKEN)).thenReturn(session(reader));
        when(sessions.authenticate(COLLECTOR_TOKEN)).thenReturn(session(collector));
        when(sessions.authenticate(PARENT_TOKEN)).thenReturn(session(guardian));
        when(finance.gatewayCaptures(any(), any(), nullable(String.class), anyString(), anyInt(), anyInt()))
                .thenAnswer(call -> {
                    assertTenant(call.getArgument(0));
                    return new GatewayCaptureQueueResponse(schoolId, date, null, List.of(),
                            call.getArgument(4), call.getArgument(5), 0, false);
                });
        when(finance.postReviewedGatewayCapture(any(), any(), any())).thenAnswer(call -> {
            assertTenant(call.getArgument(0));
            return new FinanceService.GatewayPostingResult("POSTED", paymentId, "SYN-000001");
        });
    }

    @AfterEach void close() {
        assertThat(TenantRequestContext.current()).isEmpty();
        SecurityContextHolder.clearContext();
        if (context != null) context.close();
    }

    @Test void collectionRegisterUsesActualSessionScopeAndPrivateResponseHeaders() throws Exception {
        var zero=new CollectionRegisterResponse.Summary(0,java.math.BigDecimal.ZERO,0,java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO);
        var tenders=List.of("CASH","SCHOOL_UPI","BANK_TRANSFER","CHEQUE","ONLINE_PROVIDER").stream()
                .map(method->new CollectionRegisterResponse.Tender(method,zero)).toList();
        when(finance.collections(any(),any(),any(),any(),nullable(String.class),nullable(String.class),nullable(String.class),anyInt(),anyInt()))
                .thenAnswer(call->{assertTenant(call.getArgument(0));return new CollectionRegisterResponse(schoolId,"INR",date,date,
                        null,null,0,25,0,false,zero,tenders,List.of());});
        var response=mvc.perform(get("/api/v1/finance/collections").cookie(cookie(READER_TOKEN))
                        .param("schoolId",schoolId.toString()).param("from",date.toString()).param("to",date.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.schoolId").value(schoolId.toString())).andExpect(jsonPath("$.total").value(0))
                .andReturn().getResponse();
        assertThat(response.getHeaders("Vary")).contains("Cookie");
        assertThat(response.getContentAsString()).doesNotContain("externalReference","checkoutUrl","payerPhone","credentials");
        verify(finance).collections(eq(reader),eq(schoolId),eq(date),eq(date),isNull(),isNull(),isNull(),eq(0),eq(25));
    }

    @Test void collectionRegisterRejectsAnonymousAndGuardianSessionsBeforeFinanceReads() throws Exception {
        for (String token:List.of("",PARENT_TOKEN)) {
            var request=get("/api/v1/finance/collections").param("schoolId",schoolId.toString())
                    .param("from",date.toString()).param("to",date.toString());
            if(!token.isEmpty()) request.cookie(cookie(token));
            mvc.perform(request).andExpect(status().is(token.isEmpty()?401:403));
        }
        verifyNoInteractions(finance);
    }

    @Test void collectionRegisterRejectsUnboundedAndMalformedHttpFilters() throws Exception {
        for(var invalid:List.of(new String[]{"page","10001"},new String[]{"pageSize","101"},
                new String[]{"currencyCode","INR,USD"},new String[]{"paymentMethod","ALL"},new String[]{"q","x".repeat(101)})) {
            mvc.perform(get("/api/v1/finance/collections").cookie(cookie(READER_TOKEN)).param("schoolId",schoolId.toString())
                            .param("from",date.toString()).param("to",date.toString()).param(invalid[0],invalid[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/finance/collections").cookie(cookie(READER_TOKEN))
                .param("from",date.toString()).param("to",date.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(finance);
    }

    @Test void completeCollectionExportUsesActualSessionAndPrivateHeadersWithoutPaging() throws Exception {
        var zero=new CollectionRegisterResponse.Summary(0,java.math.BigDecimal.ZERO,0,java.math.BigDecimal.ZERO,java.math.BigDecimal.ZERO);
        when(finance.exportCollections(any(),any(),any(),any(),nullable(String.class),nullable(String.class),nullable(String.class)))
                .thenAnswer(call->{assertTenant(call.getArgument(0));return new CollectionRegisterExportResponse(schoolId,
                        "Synthetic school","INR",date,date,null,null,Instant.parse("2026-10-16T10:00:00Z"),0,zero,List.of(),List.of());});
        var response=mvc.perform(get("/api/v1/finance/collections/export").cookie(cookie(READER_TOKEN))
                        .param("schoolId",schoolId.toString()).param("from",date.toString()).param("to",date.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.schoolName").value("Synthetic school")).andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").doesNotExist()).andReturn().getResponse();
        assertThat(response.getHeaders("Vary")).contains("Cookie");
        verify(finance).exportCollections(eq(reader),eq(schoolId),eq(date),eq(date),isNull(),isNull(),isNull());
    }
    @Test void completeCollectionExportRejectsAnonymousGuardianAndRevokedSessions() throws Exception {
        for(String token:List.of("",PARENT_TOKEN,"synthetic-revoked-session-token-0000001")) {
            var request=get("/api/v1/finance/collections/export").param("schoolId",schoolId.toString())
                    .param("from",date.toString()).param("to",date.toString());
            if(!token.isEmpty())request.cookie(cookie(token));
            mvc.perform(request).andExpect(status().is(token.equals(PARENT_TOKEN)?403:401));
        }
        verifyNoInteractions(finance);
    }
    @Test void completeCollectionExportRejectsMalformedHttpFiltersBeforeBulkDisclosure() throws Exception {
        for(var invalid:List.of(new String[]{"currencyCode","inr"},new String[]{"paymentMethod","ALL"},new String[]{"q","x".repeat(101)},new String[]{"from","not-a-date"})) {
            mvc.perform(get("/api/v1/finance/collections/export").cookie(cookie(READER_TOKEN)).param("schoolId",schoolId.toString())
                    .param("from",invalid[0].equals("from")?invalid[1]:date.toString()).param("to",date.toString())
                    .param(invalid[0].equals("from")?"unused":invalid[0],invalid[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/finance/collections/export").cookie(cookie(READER_TOKEN))
                .param("from",date.toString()).param("to",date.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(finance);
    }

    @Test void anonymousAndRevokedSessionsCannotReadTheActualCaptureQueue() throws Exception {
        mvc.perform(get(QUEUE).param("schoolId", schoolId.toString()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get(QUEUE).param("schoolId", schoolId.toString())
                        .cookie(new Cookie("educore_session", "synthetic-revoked-session-token-0000001")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(finance);
    }

    @Test void liveReadSessionBindsItsPrincipalAndExplicitSchoolAndBoundedQueueArguments() throws Exception {
        mvc.perform(get(QUEUE).cookie(cookie(READER_TOKEN)).param("schoolId", schoolId.toString())
                        .param("q", "Synthetic_%").param("status", "ALL").param("page", "2").param("pageSize", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schoolId").value(schoolId.toString()))
                .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
        verify(finance).gatewayCaptures(eq(reader), eq(schoolId), eq("Synthetic_%"), eq("ALL"), eq(2), eq(10));
        verify(sessions).authenticate(READER_TOKEN);
    }

    @Test void collectOnlyLiveSessionAndRawCookieHeaderPostOnlyTheReviewedRequest() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post(postingPath()).cookie(cookie(COLLECTOR_TOKEN), csrf)
                        .header("X-CSRF-TOKEN", csrf.getValue()).contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.receiptNumber").value("SYN-000001"));
        verify(finance).postReviewedGatewayCapture(eq(collector), eq(captureId),
                eq(new GatewayCapturePostingRequest(schoolId, date, "Synthetic authorized financial review")));
        verify(sessions).authenticate(COLLECTOR_TOKEN);
    }

    @Test void missingCookieOnlyHeaderOnlyAndMismatchedCsrfCannotInvokeFinancePosting() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post(postingPath()).cookie(cookie(COLLECTOR_TOKEN))
                        .contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(postingPath()).cookie(cookie(COLLECTOR_TOKEN), csrf)
                        .contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(postingPath()).cookie(cookie(COLLECTOR_TOKEN)).header("X-CSRF-TOKEN", csrf.getValue())
                        .contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(postingPath()).cookie(cookie(COLLECTOR_TOKEN), csrf)
                        .header("X-CSRF-TOKEN", "synthetic-mismatched-form-token")
                        .contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(finance);
    }

    @Test void validCsrfNeverGrantsAParentOrReadOnlyActorPostingAuthority() throws Exception {
        Cookie csrf = csrfCookie();
        for (String token : List.of(PARENT_TOKEN, READER_TOKEN)) {
            String denied = mvc.perform(post(postingPath()).cookie(cookie(token), csrf).header("X-CSRF-TOKEN", csrf.getValue())
                            .contentType("application/json").content(body()))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(denied).doesNotContain(token, "fees.collect", "AuthorizationDeniedException",
                    reader.userId().toString(), guardian.userId().toString());
        }
        mvc.perform(get(QUEUE).cookie(cookie(PARENT_TOKEN)).param("schoolId", schoolId.toString()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(finance);
    }

    @Test void validCsrfCannotReplaceAnAuthenticatedSessionOnActualPostingRoute() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post(postingPath()).cookie(csrf).header("X-CSRF-TOKEN", csrf.getValue())
                        .contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(finance);
    }

    @Test void validSessionAndCsrfStillRequireSchoolOpenDateAndBoundedReviewEvidence() throws Exception {
        Cookie csrf = csrfCookie();
        for (String invalid : List.of("{}",
                "{\"schoolId\":\"" + schoolId + "\",\"openDate\":\"2026-10-10\",\"reason\":\" \"}",
                "{\"schoolId\":\"" + schoolId + "\",\"reason\":\"Synthetic missing date\"}",
                "{\"schoolId\":\"" + schoolId + "\",\"openDate\":\"2026-10-10\",\"reason\":\"" + "x".repeat(241) + "\"}")) {
            mvc.perform(post(postingPath()).cookie(cookie(COLLECTOR_TOKEN), csrf).header("X-CSRF-TOKEN", csrf.getValue())
                            .contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(finance);
    }

    @Test void actualQueueRejectsMissingMalformedAndOutOfBoundsQueryParametersWithoutEchoingInput() throws Exception {
        for (var invalid : List.of(
                get(QUEUE),
                get(QUEUE).param("schoolId", "synthetic-invalid-school-reference"),
                get(QUEUE).param("schoolId", schoolId.toString()).param("page", "synthetic-invalid-page"),
                get(QUEUE).param("schoolId", schoolId.toString()).param("page", "-1"),
                get(QUEUE).param("schoolId", schoolId.toString()).param("page", "100001"),
                get(QUEUE).param("schoolId", schoolId.toString()).param("pageSize", "101"),
                get(QUEUE).param("schoolId", schoolId.toString()).param("q", "synthetic-private-query-".repeat(6)))) {
            String rejected = mvc.perform(invalid.cookie(cookie(READER_TOKEN)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(rejected).doesNotContain("synthetic-invalid-school-reference", "synthetic-invalid-page",
                    "synthetic-private-query", READER_TOKEN, reader.userId().toString());
        }
        verifyNoInteractions(finance);
        var invalidReturn = mock(MethodValidationResult.class);
        when(invalidReturn.isForReturnValue()).thenReturn(true);
        doThrow(new HandlerMethodValidationException(invalidReturn)).when(finance)
                .gatewayCaptures(any(), any(), nullable(String.class), anyString(), anyInt(), anyInt());
        String serverError = mvc.perform(get(QUEUE).cookie(cookie(READER_TOKEN)).param("schoolId", schoolId.toString()))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andReturn().getResponse().getContentAsString();
        assertThat(serverError).doesNotContain("Validation failure", "HandlerMethodValidationException",
                READER_TOKEN, reader.userId().toString());
    }

    @Test void untrustedOriginCannotReadOrPostEvenWithAValidFinancialSessionAndCsrf() throws Exception {
        Cookie csrf = csrfCookie();
        mvc.perform(post(postingPath()).cookie(cookie(COLLECTOR_TOKEN), csrf).header("X-CSRF-TOKEN", csrf.getValue())
                        .header("Origin", "https://untrusted.example.test").contentType("application/json").content(body()))
                .andExpect(status().isForbidden());
        mvc.perform(get(QUEUE).cookie(cookie(READER_TOKEN)).param("schoolId", schoolId.toString())
                        .header("Origin", "https://untrusted.example.test"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(finance);
    }

    private Cookie csrfCookie() throws Exception {
        Cookie csrf = mvc.perform(get(QUEUE).cookie(cookie(READER_TOKEN)).param("schoolId", schoolId.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("educore_csrf");
        assertThat(csrf).isNotNull();
        clearInvocations(finance);
        return csrf;
    }
    private Cookie cookie(String token) { return new Cookie("educore_session", token); }
    private String postingPath() { return QUEUE + "/" + captureId + "/posting"; }
    private String body() {
        return "{\"schoolId\":\"" + schoolId
                + "\",\"openDate\":\"2026-10-10\",\"reason\":\"Synthetic authorized financial review\"}";
    }
    private AuthenticatedUser actor(String name, String capability) {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic-finance", null, name,
                true, organisationId, Set.of(UUID.randomUUID()), Set.of(capability), List.of());
    }
    private Optional<SessionService.AuthenticatedSession> session(AuthenticatedUser actor) {
        return Optional.of(new SessionService.AuthenticatedSession(actor, Instant.now().plusSeconds(60)));
    }
    private void assertTenant(AuthenticatedUser actor) {
        var scope = TenantRequestContext.current().orElseThrow();
        assertThat(scope.userId()).isEqualTo(actor.userId());
        assertThat(scope.organisationId()).isEqualTo(actor.activeOrganisationId());
    }

    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfiguration.class)
    static class Config {
        @Bean FinanceService finance() { return mock(FinanceService.class); }
        @Bean FinanceController controller(FinanceService finance) { return new FinanceController(finance); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean GlobalExceptionHandler advice(Clock clock) { return new GlobalExceptionHandler(clock); }
        @Bean ApiSecurityErrorWriter errors(Clock clock) { return new ApiSecurityErrorWriter(new ObjectMapper(), clock); }
        @Bean SessionService sessions() { return mock(SessionService.class); }
        @Bean PrivilegedMfaPolicy mfa() { return mock(PrivilegedMfaPolicy.class); }
        @Bean ApplicationProperties properties() {
            var properties = mock(ApplicationProperties.class);
            var security = mock(ApplicationProperties.Security.class);
            when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));
            when(properties.security()).thenReturn(security);
            when(security.sessionCookieName()).thenReturn("educore_session");
            return properties;
        }
        @Bean SessionAuthenticationFilter filter(SessionService sessions, ApplicationProperties properties,
                PrivilegedMfaPolicy mfa, ApiSecurityErrorWriter errors) {
            return new SessionAuthenticationFilter(sessions, properties, mfa, errors);
        }
    }
}
