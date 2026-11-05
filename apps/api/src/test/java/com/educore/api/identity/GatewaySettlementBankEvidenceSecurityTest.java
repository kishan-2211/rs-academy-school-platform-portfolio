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
import com.educore.api.finance.GatewaySettlementBankEvidenceController;
import com.educore.api.finance.GatewaySettlementBankEvidenceModels;
import com.educore.api.finance.GatewaySettlementBankEvidenceService;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Actual session/CSRF/CORS/MVC boundaries with a mocked domain. No DB, provider or browser claims. */
class GatewaySettlementBankEvidenceSecurityTest {
    private static final Instant AT=Instant.parse("2026-10-25T08:00:00.123456789Z");
    private static final String PRIVATE_MARKER="synthetic-private-bank-reference";
    private final UUID organisation=UUID.randomUUID(),school=UUID.randomUUID(),request=UUID.randomUUID(),
            evidence=UUID.randomUUID(),client=UUID.randomUUID(),bank=UUID.randomUUID(),read=UUID.randomUUID();
    private final Map<String,AuthenticatedUser> actors=new LinkedHashMap<>();
    private AnnotationConfigWebApplicationContext application;
    private MockMvc mvc;
    private GatewaySettlementBankEvidenceService service;

    @BeforeEach void setup() {
        actors.put("reader",actor("fees.read","accounting.read"));
        actors.put("proposer",actor("fees.read","accounting.read","fees.close.propose"));
        actors.put("checker",actor("fees.read","accounting.read","fees.close.approve"));
        actors.put("fees-only",actor("fees.read"));
        actors.put("books-only",actor("accounting.read"));
        actors.put("propose-only",actor("fees.close.propose"));
        actors.put("approve-only",actor("fees.close.approve"));
        actors.put("guardian",actor("portal.guardian"));
        application=new AnnotationConfigWebApplicationContext();application.setServletContext(new MockServletContext());
        application.register(Config.class);application.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(application).apply(springSecurity()).build();
        service=application.getBean(GatewaySettlementBankEvidenceService.class);
        var sessions=application.getBean(SessionService.class);
        actors.forEach((name,user)->when(sessions.authenticate(token(name))).thenReturn(Optional.of(
                new SessionService.AuthenticatedSession(user,Instant.now().plusSeconds(3600)))));
        when(service.context(any(),any(),any())).thenAnswer(call->{
            AuthenticatedUser user=call.getArgument(0);assertTenant(user);
            return context(user);
        });
        when(service.record(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return recorded(false);});
        when(service.reject(any(),any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return rejected();});
    }

    @AfterEach void cleanup() {
        assertThat(TenantRequestContext.current()).isEmpty();SecurityContextHolder.clearContext();
        if(application!=null)application.close();
    }

    @Test void anonymousRevokedGuardianAndEitherSingleReadCapabilityCannotReadBankEvidence() throws Exception {
        for(String name:List.of("","revoked","guardian","fees-only","books-only","propose-only","approve-only")) {
            var call=get(path()).param("schoolId",school.toString());if(!name.isEmpty())call.cookie(auth(name));
            var response=mvc.perform(call).andExpect(status().is(actors.containsKey(name)?403:401));
            filterPrivate(response);
        }
        verifyNoInteractions(service);
    }

    @Test void combinedReaderGetsPrivateNonPostingContextAndExplicitRetainedEvidence() throws Exception {
        var response=mvc.perform(get(path()).cookie(auth("reader")).param("schoolId",school.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schoolId").value(school.toString()))
                .andExpect(jsonPath("$.requestId").value(request.toString()))
                .andExpect(jsonPath("$.canRecord").value(false)).andExpect(jsonPath("$.canReject").value(false))
                .andExpect(jsonPath("$.evidence[0].status").value("BANK_EVIDENCE_RECORDED"))
                .andExpect(jsonPath("$.evidence[0].rejection").isEmpty())
                .andExpect(jsonPath("$.evidence[0].creditedAmount").value("87.65"))
                .andExpect(jsonPath("$.bankConfirmed").doesNotExist()).andExpect(jsonPath("$.canPost").doesNotExist())
                .andExpect(jsonPath("$.reconciled").doesNotExist());
        privateResponse(response);
        assertNoInfrastructure(response.andReturn().getResponse());
        verify(service).context(actors.get("reader"),school,request);
        verify(service,never()).record(any(),any(),any());verify(service,never()).reject(any(),any(),any(),any());
    }

    @Test void recordingRequiresBothReadCapabilitiesAndProposeEvenWithValidCsrf() throws Exception {
        var csrf=csrf();
        for(String name:List.of("reader","checker","guardian","fees-only","books-only","propose-only","approve-only"))
            privateResponse(mvc.perform(post(path()).cookie(auth(name),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                    .contentType("application/json").content(body())).andExpect(status().isForbidden()));
        verifyNoInteractions(service);
    }

    @Test void rejectionRequiresBothReadCapabilitiesAndApproveEvenWithValidCsrf() throws Exception {
        var csrf=csrf();
        for(String name:List.of("reader","proposer","guardian","fees-only","books-only","propose-only","approve-only"))
            privateResponse(mvc.perform(post(rejectionPath()).cookie(auth(name),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                    .contentType("application/json").content(rejectionBody())).andExpect(status().isForbidden()));
        verifyNoInteractions(service);
    }

    @Test void neitherCommandAcceptsAnAbsentOrRevokedSessionEvenWithValidCsrf() throws Exception {
        var csrf=csrf();
        for(String endpoint:List.of(path(),rejectionPath()))for(String name:List.of("","revoked")) {
            var call=post(endpoint).cookie(csrf).header("X-CSRF-TOKEN",csrf.getValue())
                    .contentType("application/json").content(endpoint.equals(path())?body():rejectionBody());
            if(!name.isEmpty())call.cookie(auth(name));
            filterPrivate(mvc.perform(call).andExpect(status().isUnauthorized()));
        }
        verifyNoInteractions(service);
    }

    @Test void bothCommandsRequireTheActualMatchingCsrfCookieAndHeader() throws Exception {
        var csrf=csrf();
        for(String endpoint:List.of(path(),rejectionPath())) {
            String name=endpoint.equals(path())?"proposer":"checker",payload=endpoint.equals(path())?body():rejectionBody();
            filterPrivate(mvc.perform(post(endpoint).cookie(auth(name)).contentType("application/json").content(payload)).andExpect(status().isForbidden()));
            filterPrivate(mvc.perform(post(endpoint).cookie(auth(name),csrf).contentType("application/json").content(payload)).andExpect(status().isForbidden()));
            filterPrivate(mvc.perform(post(endpoint).cookie(auth(name)).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(payload)).andExpect(status().isForbidden()));
            filterPrivate(mvc.perform(post(endpoint).cookie(auth(name),csrf).header("X-CSRF-TOKEN","synthetic-mismatched-csrf").contentType("application/json").content(payload)).andExpect(status().isForbidden()));
        }
        verifyNoInteractions(service);
    }

    @Test void actualProposerAndMatchingCsrfBindExactAttestationToCurrentHumanTenant() throws Exception {
        var csrf=csrf();var response=mvc.perform(post(path()).cookie(auth("proposer"),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(body())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(evidence.toString())).andExpect(jsonPath("$.status").value("BANK_EVIDENCE_RECORDED"))
                .andExpect(jsonPath("$.rejection").isEmpty()).andExpect(jsonPath("$.canReject").value(false))
                .andExpect(jsonPath("$.canPost").doesNotExist());
        privateResponse(response);assertNoInfrastructure(response.andReturn().getResponse());
        verify(service).record(actors.get("proposer"),request,new GatewaySettlementBankEvidenceModels.Command(
                school,client,bank,"INR","87.65",AT,"BANK_STATEMENT_CREDIT",PRIVATE_MARKER,true,"Synthetic actual-credit review"));
        verify(service,never()).context(any(),any(),any());verify(service,never()).reject(any(),any(),any(),any());
    }

    @Test void distinctCheckerAndMatchingCsrfBindRetainedEvidenceRejectionWithoutDeletion() throws Exception {
        assertThat(actors.get("checker").userId()).isNotEqualTo(actors.get("proposer").userId());
        var csrf=csrf();var response=mvc.perform(post(rejectionPath()).cookie(auth("checker"),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(rejectionBody())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(evidence.toString())).andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejection.reason").value("Synthetic mistaken-credit evidence"))
                .andExpect(jsonPath("$.canReject").value(false)).andExpect(jsonPath("$.canPost").doesNotExist());
        privateResponse(response);assertNoInfrastructure(response.andReturn().getResponse());
        verify(service).reject(actors.get("checker"),request,evidence,new GatewaySettlementBankEvidenceModels.RejectionCommand(
                school,client,"Synthetic mistaken-credit evidence",true));
        verify(service,never()).context(any(),any(),any());verify(service,never()).record(any(),any(),any());
    }

    @Test void malformedAndBoundedCommandsFailBeforeServiceWithoutEchoingPrivateInput() throws Exception {
        var csrf=csrf();
        for(String invalid:List.of("{}","{\"bankCreditReference\":\""+PRIVATE_MARKER,
                body().replace("\"INR\"","\"USD\""),body().replace("\"87.65\"","\"1234567890123\""),
                body().replace(PRIVATE_MARKER,PRIVATE_MARKER.repeat(5)),body().replace("Synthetic actual-credit review","x".repeat(241)),
                body().replace("\"confirmedReceived\":true","\"confirmedReceived\":false"),body().replace(AT.toString(),"synthetic-invalid-time"))) {
            var response=mvc.perform(post(path()).cookie(auth("proposer"),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
            privateResponse(response);assertNoPrivateEcho(response.andReturn().getResponse());
        }
        for(String invalid:List.of("{}",rejectionBody().replace("Synthetic mistaken-credit evidence","x".repeat(241)),
                rejectionBody().replace("\"confirmedMistakenEvidence\":true","\"confirmedMistakenEvidence\":false"))) {
            var response=mvc.perform(post(rejectionPath()).cookie(auth("checker"),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                    .contentType("application/json").content(invalid)).andExpect(status().isBadRequest());
            privateResponse(response);assertNoPrivateEcho(response.andReturn().getResponse());
        }
        verifyNoInteractions(service);
    }

    @Test void missingOrMalformedScopeIdentifiersArePrivate400WithoutDomainInvocation() throws Exception {
        for(var call:List.of(get(path()).cookie(auth("reader")),
                get(path()).cookie(auth("reader")).param("schoolId",PRIVATE_MARKER),
                get("/api/v1/finance/gateway/settlements/requests/synthetic-invalid-request-id/bank-evidence")
                        .cookie(auth("reader")).param("schoolId",school.toString()))) {
            var response=mvc.perform(call).andExpect(status().isBadRequest());privateResponse(response);
            assertNoPrivateEcho(response.andReturn().getResponse());
        }
        verifyNoInteractions(service);
    }

    @Test void untrustedOriginCannotInspectRecordOrRejectWithOtherwiseValidSessionAndCsrf() throws Exception {
        var csrf=csrf();String origin="https://synthetic-untrusted-bank.example.test";
        filterPrivate(mvc.perform(get(path()).cookie(auth("reader")).param("schoolId",school.toString()).header("Origin",origin)).andExpect(status().isForbidden()));
        filterPrivate(mvc.perform(post(path()).cookie(auth("proposer"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).header("Origin",origin)
                .contentType("application/json").content(body())).andExpect(status().isForbidden()));
        filterPrivate(mvc.perform(post(rejectionPath()).cookie(auth("checker"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).header("Origin",origin)
                .contentType("application/json").content(rejectionBody())).andExpect(status().isForbidden()));
        verifyNoInteractions(service);
    }

    @Test void deleteIsPrivate405AndCannotDeleteEvidenceOrItsCorrectionHistory() throws Exception {
        var csrf=csrf();
        for(String endpoint:List.of(path(),rejectionPath())) {
            var response=mvc.perform(delete(endpoint).cookie(auth("checker"),csrf).header("X-CSRF-TOKEN",csrf.getValue()))
                    .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
            privateResponse(response);assertNoInfrastructure(response.andReturn().getResponse());
        }
        verifyNoInteractions(service);
    }

    @Test void currentDomainScopeDenialAndReplayConflictRemainControlledPrivateErrors() throws Exception {
        var csrf=csrf();
        for(HttpStatus status:List.of(HttpStatus.FORBIDDEN,HttpStatus.CONFLICT)) {
            String code=status==HttpStatus.FORBIDDEN?"GATEWAY_SETTLEMENT_SCOPE_DENIED":"GATEWAY_SETTLEMENT_BANK_EVIDENCE_CONFLICT";
            var failure=new ApiException(status,code,"Refresh the authorized synthetic bank-evidence review.");
            doThrow(failure).when(service).context(any(),any(),any());
            doThrow(failure).when(service).record(any(),any(),any());
            doThrow(failure).when(service).reject(any(),any(),any(),any());
            for(var response:List.of(
                    mvc.perform(get(path()).cookie(auth("reader")).param("schoolId",school.toString())),
                    mvc.perform(post(path()).cookie(auth("proposer"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body())),
                    mvc.perform(post(rejectionPath()).cookie(auth("checker"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(rejectionBody())))) {
                response.andExpect(status().is(status.value())).andExpect(jsonPath("$.code").value(code));
                privateResponse(response);assertNoPrivateEcho(response.andReturn().getResponse());
            }
        }
    }

    private String path(){return "/api/v1/finance/gateway/settlements/requests/"+request+"/bank-evidence";}
    private String rejectionPath(){return path()+"/"+evidence+"/rejections";}
    private static String token(String name){return "synthetic-bank-evidence-"+name+"-session-00001";}
    private static Cookie auth(String name){return new Cookie("educore_session",token(name));}
    private Cookie csrf() throws Exception {
        var response=mvc.perform(get(path()).cookie(auth("reader")).param("schoolId",school.toString())).andExpect(status().isOk()).andReturn().getResponse();
        var csrf=response.getCookie("educore_csrf");assertThat(csrf).isNotNull();clearInvocations(service);return csrf;
    }
    private static void privateResponse(ResultActions response) throws Exception {
        response.andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"));
        assertThat(response.andReturn().getResponse().getHeaders("Vary")).contains("Cookie");
    }
    private static void filterPrivate(ResultActions response) throws Exception {
        response.andExpect(header().string("X-Content-Type-Options","nosniff"));
        assertThat(response.andReturn().getResponse().getHeader("Cache-Control")).contains("no-store");
    }
    private void assertNoPrivateEcho(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_MARKER,token("reader"),token("proposer"),token("checker"),"HttpMessageNotReadableException");
    }
    private static void assertNoInfrastructure(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentAsString()).doesNotContain("credentials","merchant","cursor","clientSecret","providerEnabled","accountingJournalId","canPost");
    }
    private String body(){return "{\"schoolId\":\""+school+"\",\"clientRequestId\":\""+client+"\",\"bankAccountId\":\""+bank
            +"\",\"currencyCode\":\"INR\",\"creditedAmount\":\"87.65\",\"actualCreditedAt\":\""+AT
            +"\",\"verificationKind\":\"BANK_STATEMENT_CREDIT\",\"bankCreditReference\":\""+PRIVATE_MARKER
            +"\",\"confirmedReceived\":true,\"reason\":\"Synthetic actual-credit review\"}";}
    private String rejectionBody(){return "{\"schoolId\":\""+school+"\",\"clientRequestId\":\""+client
            +"\",\"reason\":\"Synthetic mistaken-credit evidence\",\"confirmedMistakenEvidence\":true}";}
    private AuthenticatedUser actor(String...capabilities){return new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),
            "synthetic-bank@example.test",null,"Synthetic bank-evidence actor",true,organisation,Set.of(UUID.randomUUID()),Set.of(capabilities),List.of());}
    private static void assertTenant(AuthenticatedUser user){var scope=TenantRequestContext.current().orElseThrow();
        assertThat(scope.userId()).isEqualTo(user.userId());assertThat(scope.organisationId()).isEqualTo(user.activeOrganisationId());}
    private GatewaySettlementBankEvidenceModels.Context context(AuthenticatedUser user){return new GatewaySettlementBankEvidenceModels.Context(
            school,request,"synthetic_statement","Asia/Kolkata",user.capabilities().contains("fees.close.propose"),user.capabilities().contains("fees.close.approve"),
            new GatewaySettlementBankEvidenceModels.Summary(read,0,AT,"SUCCESS","synthetic_transfer","87.65"),
            List.of(new GatewaySettlementBankEvidenceModels.BankAccount(bank,"SYNTHETIC_BANK","Synthetic bank chart","INR")),
            List.of(recorded(user.capabilities().contains("fees.close.approve"))),false);}
    private GatewaySettlementBankEvidenceModels.Evidence recorded(boolean canReject){return evidence("BANK_EVIDENCE_RECORDED",null,canReject);}
    private GatewaySettlementBankEvidenceModels.Evidence rejected(){return evidence("REJECTED",new GatewaySettlementBankEvidenceModels.Rejection(
            UUID.randomUUID(),"Synthetic mistaken-credit evidence",AT.plusSeconds(1),"Synthetic independent checker"),false);}
    private GatewaySettlementBankEvidenceModels.Evidence evidence(String status,GatewaySettlementBankEvidenceModels.Rejection rejection,boolean canReject){return new GatewaySettlementBankEvidenceModels.Evidence(
            evidence,client,bank,PRIVATE_MARKER,"INR","87.65",AT.toString(),LocalDate.of(2026,10,25),"BANK_STATEMENT_CREDIT",AT,
            "Synthetic maker","Synthetic actual-credit review",status,List.of("NOT_RECONCILED","SUMMARY_CURRENCY_UNCONFIRMED","SOURCE_RECONCILIATION_PENDING"),rejection,canReject);}

    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config {
        @Bean GatewaySettlementBankEvidenceService service(){return mock(GatewaySettlementBankEvidenceService.class);}
        @Bean GatewaySettlementBankEvidenceController controller(GatewaySettlementBankEvidenceService service){return new GatewaySettlementBankEvidenceController(service);}
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean GlobalExceptionHandler advice(Clock clock){return new GlobalExceptionHandler(clock);}
        @Bean ApiSecurityErrorWriter errors(Clock clock){return new ApiSecurityErrorWriter(new ObjectMapper(),clock);}
        @Bean SessionService sessions(){return mock(SessionService.class);}
        @Bean PrivilegedMfaPolicy mfa(){return mock(PrivilegedMfaPolicy.class);}
        @Bean ApplicationProperties properties(){var properties=mock(ApplicationProperties.class);var security=mock(ApplicationProperties.Security.class);
            when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));when(properties.security()).thenReturn(security);
            when(security.sessionCookieName()).thenReturn("educore_session");return properties;}
        @Bean SessionAuthenticationFilter filter(SessionService sessions,ApplicationProperties properties,PrivilegedMfaPolicy mfa,ApiSecurityErrorWriter errors){
            return new SessionAuthenticationFilter(sessions,properties,mfa,errors);}
    }
}
