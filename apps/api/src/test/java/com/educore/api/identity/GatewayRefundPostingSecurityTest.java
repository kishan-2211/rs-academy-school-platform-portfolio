package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.educore.api.common.api.*;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.SecurityConfiguration;
import com.educore.api.finance.*;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import tools.jackson.databind.ObjectMapper;

/** Actual session/filter/CSRF/CORS/controller gates; mocked domain service is not financial or provider proof. */
class GatewayRefundPostingSecurityTest {
    private static final String READ="synthetic-refund-post-read-session-000001", WRITE="synthetic-refund-post-write-session-00001";
    private static final String PARENT="synthetic-refund-post-parent-session-0001", STUDENT="synthetic-refund-post-student-session-001";
    private static final String NO_REFUND="synthetic-refund-post-no-refund-session01", NO_PREPARE="synthetic-refund-post-no-prepare-session1";
    private static final String NO_ACCOUNTING_READ="synthetic-refund-post-no-read-session001", FOREIGN="synthetic-refund-post-foreign-session01";
    private final UUID org=UUID.randomUUID(), school=UUID.randomUUID(), refund=UUID.randomUUID(), account=UUID.randomUUID();
    private final UUID payment=UUID.randomUUID(), clientId=UUID.randomUUID(), year=UUID.randomUUID(), completion=UUID.randomUUID();
    private final AuthenticatedUser reader=actor(org,"fees.read"), writer=actor(org,"fees.read","fees.refund","accounting.prepare","accounting.read");
    private final AuthenticatedUser parent=actor(org,"portal.guardian"), student=actor(org,"portal.student");
    private final AuthenticatedUser foreign=actor(UUID.randomUUID(),"fees.read","fees.refund","accounting.prepare","accounting.read");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private GatewayRefundPostingService service;

    @BeforeEach void setup() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();service=context.getBean(GatewayRefundPostingService.class);
        var sessions=context.getBean(SessionService.class);
        when(sessions.authenticate(READ)).thenReturn(session(reader));when(sessions.authenticate(WRITE)).thenReturn(session(writer));
        when(sessions.authenticate(PARENT)).thenReturn(session(parent));when(sessions.authenticate(STUDENT)).thenReturn(session(student));
        when(sessions.authenticate(FOREIGN)).thenReturn(session(foreign));
        when(sessions.authenticate(NO_REFUND)).thenReturn(session(actor(org,"fees.read","accounting.prepare","accounting.read")));
        when(sessions.authenticate(NO_PREPARE)).thenReturn(session(actor(org,"fees.read","fees.refund","accounting.read")));
        when(sessions.authenticate(NO_ACCOUNTING_READ)).thenReturn(session(actor(org,"fees.read","fees.refund","accounting.prepare")));
        when(service.inspect(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return readContext();});
        when(service.post(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return posted();});
        when(service.repairJournal(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new GatewayRefundPostingModels.JournalRepair(
                completion,refund,account,payment,UUID.randomUUID(),UUID.randomUUID(),"SUBMITTED",year,LocalDate.of(2026,10,20));});
    }

    @AfterEach void close() {
        assertThat(TenantRequestContext.current()).isEmpty();SecurityContextHolder.clearContext();if(context!=null)context.close();
    }

    @Test void anonymousRevokedParentAndStudentCannotReadStaffRefundPostingContext() throws Exception {
        for(String token:List.of("","synthetic-refund-post-revoked-session01",PARENT,STUDENT)) {
            var request=get(path()).param("schoolId",school.toString());if(!token.isEmpty())request.cookie(cookie(token));
            mvc.perform(request).andExpect(status().is(token.equals(PARENT)||token.equals(STUDENT)?403:401));
        }
        Cookie csrf=csrf();
        mvc.perform(post(path()).cookie(csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void feesReadAloneCanInspectMinimalPrivateContextWithoutAccountingReadOrFinancialAuthority() throws Exception {
        var response=mvc.perform(get(path()).cookie(cookie(READ)).param("schoolId",school.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.schoolId").value(school.toString())).andExpect(jsonPath("$.refundId").value(refund.toString()))
                .andExpect(jsonPath("$.canPost").value(false)).andExpect(jsonPath("$.sourceState").value("READY"))
                .andReturn().getResponse();
        assertThat(response.getHeaders("Vary")).contains("Cookie");
        assertThat(response.getContentAsString()).contains("\"posting\":null");assertPrivate(response.getContentAsString());
        verify(service).inspect(reader,school,refund);
    }

    @Test void everyMissingWriteCapabilityAndFamilyRoleIsDeniedDespiteActualCsrf() throws Exception {
        Cookie csrf=csrf();
        for(String token:List.of(READ,PARENT,STUDENT,NO_REFUND,NO_PREPARE,NO_ACCOUNTING_READ))
            mvc.perform(post(path()).cookie(cookie(token),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body()))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void missingMismatchedAndOneSidedCsrfCannotRecordFinancialCompletion() throws Exception {
        Cookie csrf=csrf();
        mvc.perform(post(path()).cookie(cookie(WRITE)).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(path()).cookie(cookie(WRITE),csrf).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(path()).cookie(cookie(WRITE)).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(path()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN","synthetic-mismatched-token").contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void actualWriterSessionBindsOnlyReviewedSchoolDateYearReasonAndStableRequestIdentity() throws Exception {
        Cookie csrf=csrf();
        var response=mvc.perform(post(path()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.id").value(completion.toString())).andExpect(jsonPath("$.journalStatus").value("SUBMITTED"))
                .andExpect(jsonPath("$.amount").value(30.25)).andReturn().getResponse();
        verify(service).post(writer,refund,new GatewayRefundPostingModels.Command(school,clientId,year,LocalDate.of(2026,10,20),"Synthetic reviewed financial posting"));
        assertThat(response.getHeaders("Vary")).contains("Cookie");assertPrivate(response.getContentAsString());
    }

    @Test void invalidBodyAndIdentifiersFailBeforeServiceWithoutEchoingPrivateInput() throws Exception {
        Cookie csrf=csrf();
        for(String invalid:List.of("{}",body().replace(clientId.toString(),"not-a-private-request-id"),
                body().replace("2026-10-20","synthetic-private-date"),body().replace("Synthetic reviewed financial posting","x"),
                body().replace("Synthetic reviewed financial posting","synthetic-private-reason".repeat(40)))) {
            String response=mvc.perform(post(path()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("not-a-private-request-id","synthetic-private-date","synthetic-private-reason",WRITE);
        }
        mvc.perform(get(path()).cookie(cookie(READ))).andExpect(status().isBadRequest());
        mvc.perform(get(path()).cookie(cookie(READ)).param("schoolId","synthetic-private-school-id")).andExpect(status().isBadRequest());
        mvc.perform(get(path().replace(refund.toString(),"synthetic-private-refund-id")).cookie(cookie(READ)).param("schoolId",school.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void foreignSessionAndForeignSchoolDenialsRemainSanitizedWithActualTenantContext() throws Exception {
        Cookie csrf=csrf();UUID otherSchool=UUID.randomUUID();
        doAnswer(call->{assertTenant(call.getArgument(0));throw new ApiException(HttpStatus.NOT_FOUND,"REFUND_POSTING_NOT_FOUND","Refund posting is unavailable for this school.");})
                .when(service).inspect(any(),any(),any());
        doAnswer(call->{assertTenant(call.getArgument(0));throw new ApiException(HttpStatus.NOT_FOUND,"REFUND_POSTING_NOT_FOUND","Refund posting is unavailable for this school.");})
                .when(service).post(any(),any(),any());
        for(String token:List.of(READ,FOREIGN)) {
            String response=mvc.perform(get(path()).cookie(cookie(token)).param("schoolId",otherSchool.toString()))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("Synthetic learner",payment.toString(),account.toString());assertPrivate(response);
        }
        mvc.perform(post(path()).cookie(cookie(FOREIGN),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body()))
                .andExpect(status().isNotFound());
        verify(service).inspect(foreign,otherSchool,refund);
        verify(service).post(eq(foreign),eq(refund),any());
    }

    @Test void untrustedOriginsCannotUseOtherwiseValidSessionAndCsrf() throws Exception {
        Cookie csrf=csrf();
        mvc.perform(get(path()).cookie(cookie(READ)).param("schoolId",school.toString()).header("Origin","https://synthetic-untrusted.example.test"))
                .andExpect(status().isForbidden());
        mvc.perform(post(path()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).header("Origin","https://synthetic-untrusted.example.test")
                .contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void unexpectedServerFaultDoesNotExposeProviderOrCredentialDetails() throws Exception {
        doThrow(new IllegalStateException("synthetic-private-provider-secret-and-reference")).when(service).inspect(any(),any(),any());
        String response=mvc.perform(get(path()).cookie(cookie(READ)).param("schoolId",school.toString()))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-private-provider-secret-and-reference",READ,"IllegalStateException");
    }

    @Test void journalRepairRequiresAllThreeCapabilitiesAndActualMatchingCsrf() throws Exception {
        Cookie csrf=csrf();
        for(String token:List.of(READ,PARENT,STUDENT,NO_REFUND,NO_PREPARE,NO_ACCOUNTING_READ))
            mvc.perform(post(path()+"/journal-repairs").cookie(cookie(token),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(repairBody()))
                    .andExpect(status().isForbidden());
        mvc.perform(post(path()+"/journal-repairs").cookie(cookie(WRITE)).contentType("application/json").content(repairBody())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void journalRepairUsesPrivateResponseAndExactSelectedPredecessorWithoutClientMoney() throws Exception {
        Cookie csrf=csrf();
        var response=mvc.perform(post(path()+"/journal-repairs").cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(repairBody()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.refundPostingId").value(completion.toString()))
                .andExpect(jsonPath("$.previousBatchId").value(account.toString())).andExpect(jsonPath("$.previousJournalId").value(payment.toString()))
                .andExpect(jsonPath("$.journalStatus").value("SUBMITTED")).andReturn().getResponse();
        verify(service).repairJournal(writer,refund,new GatewayRefundPostingModels.JournalRepairCommand(school,clientId,account,payment,year,LocalDate.of(2026,10,20),"Synthetic reviewed financial posting"));
        assertThat(response.getHeaders("Vary")).contains("Cookie");assertPrivate(response.getContentAsString());
    }

    @Test void journalRepairRejectsMissingPredecessorInvalidReasonAndUntrustedOriginBeforeService() throws Exception {
        Cookie csrf=csrf();
        for(String invalid:List.of("{}",body(),repairBody().replace(account.toString(),"synthetic-private-bad-batch"),repairBody().replace("Synthetic reviewed financial posting","x")))
            mvc.perform(post(path()+"/journal-repairs").cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(invalid))
                    .andExpect(status().isBadRequest());
        mvc.perform(post(path()+"/journal-repairs").cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).header("Origin","https://synthetic-untrusted.example.test")
                .contentType("application/json").content(repairBody())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void journalRepairForeignScopeAndUnexpectedFailuresDoNotExposeFinancialDetails() throws Exception {
        Cookie csrf=csrf();
        doThrow(new ApiException(HttpStatus.NOT_FOUND,"REFUND_POSTING_NOT_FOUND","Refund posting is unavailable for this school.")).when(service).repairJournal(eq(foreign),any(),any());
        mvc.perform(post(path()+"/journal-repairs").cookie(cookie(FOREIGN),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(repairBody())).andExpect(status().isNotFound());
        doThrow(new IllegalStateException("synthetic-private-immutable-repair-secret")).when(service).repairJournal(eq(writer),any(),any());
        String response=mvc.perform(post(path()+"/journal-repairs").cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(repairBody()))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-private-immutable-repair-secret",WRITE);assertPrivate(response);
    }

    private Cookie csrf() throws Exception {
        Cookie value=mvc.perform(get(path()).cookie(cookie(READ)).param("schoolId",school.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getCookie("educore_csrf");assertThat(value).isNotNull();clearInvocations(service);return value;
    }
    private String path(){return "/api/v1/finance/gateway/refunds/"+refund+"/posting";}
    private Cookie cookie(String value){return new Cookie("educore_session",value);}
    private String body(){return "{\"schoolId\":\""+school+"\",\"clientRequestId\":\""+clientId+"\",\"financialYearId\":\""+year+"\",\"postingOn\":\"2026-10-20\",\"reason\":\"Synthetic reviewed financial posting\"}";}
    private String repairBody(){return body().replace("\"financialYearId\"","\"expectedBatchId\":\""+account+"\",\"expectedJournalId\":\""+payment+"\",\"financialYearId\"");}
    private AuthenticatedUser actor(UUID organisation,String...capabilities){return new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic-refund-posting",null,"Synthetic refund reader",true,organisation,Set.of(UUID.randomUUID()),Set.of(capabilities),List.of());}
    private Optional<SessionService.AuthenticatedSession> session(AuthenticatedUser user){return Optional.of(new SessionService.AuthenticatedSession(user,Instant.now().plusSeconds(60)));}
    private void assertTenant(AuthenticatedUser user){var tenant=TenantRequestContext.current().orElseThrow();assertThat(tenant.userId()).isEqualTo(user.userId());assertThat(tenant.organisationId()).isEqualTo(user.activeOrganisationId());}
    private GatewayRefundPostingModels.Context readContext(){return new GatewayRefundPostingModels.Context(school,refund,payment,"SYN-000001",account,"Synthetic learner","Synthetic historical session","ACTIVE","INR",new BigDecimal("800.00"),new BigDecimal("30.25"),"READY","2026-10-20T06:00:00.123456789Z",LocalDate.of(2026,10,20),"Asia/Kolkata",false,false,null);}
    private GatewayRefundPostingModels.Posting posted(){return new GatewayRefundPostingModels.Posting(completion,refund,payment,account,new BigDecimal("30.25"),"INR",LocalDate.of(2026,10,20),Instant.parse("2026-10-20T06:01:00Z"),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"SUBMITTED");}
    private static void assertPrivate(String body){assertThat(body).doesNotContain("credentials_secret_reference","merchant_account_reference","payment_reference","order_reference","evidence_sha256","payerPhone","providerCode");}

    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config {
        @Bean GatewayRefundPostingService postings(){return mock(GatewayRefundPostingService.class);}
        @Bean GatewayRefundPostingController controller(GatewayRefundPostingService service){return new GatewayRefundPostingController(service);}
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean GlobalExceptionHandler advice(Clock clock){return new GlobalExceptionHandler(clock);}
        @Bean ApiSecurityErrorWriter errors(Clock clock){return new ApiSecurityErrorWriter(new ObjectMapper(),clock);}
        @Bean SessionService sessions(){return mock(SessionService.class);}
        @Bean PrivilegedMfaPolicy mfa(){return mock(PrivilegedMfaPolicy.class);}
        @Bean ApplicationProperties properties(){var properties=mock(ApplicationProperties.class);var security=mock(ApplicationProperties.Security.class);when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));when(properties.security()).thenReturn(security);when(security.sessionCookieName()).thenReturn("educore_session");return properties;}
        @Bean SessionAuthenticationFilter filter(SessionService sessions,ApplicationProperties properties,PrivilegedMfaPolicy mfa,ApiSecurityErrorWriter errors){return new SessionAuthenticationFilter(sessions,properties,mfa,errors);}
    }
}
