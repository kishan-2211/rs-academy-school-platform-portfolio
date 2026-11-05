package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.educore.api.accounting.AccountingModuleBridge.SettlementAccountingOptions;
import com.educore.api.common.api.ApiException;
import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.common.api.GlobalExceptionHandler;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.SecurityConfiguration;
import com.educore.api.finance.GatewaySettlementBankEvidenceModels;
import com.educore.api.finance.GatewaySettlementModels;
import com.educore.api.finance.GatewaySettlementPreparationController;
import com.educore.api.finance.GatewaySettlementPreparationModels;
import com.educore.api.finance.GatewaySettlementPreparationService;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

/** Actual session, tenant, CSRF, CORS and MVC boundaries; monetary invariants are tested in PostgreSQL. */
class GatewaySettlementPreparationSecurityTest {
    private static final Instant AT=Instant.parse("2026-10-25T08:00:00.123456789Z");
    private static final String PRIVATE_MARKER="synthetic-private-preparation-reference";
    private static final String FINGERPRINT="a".repeat(64);
    private static final List<String> KEYS=List.of("SERVICE_CHARGE","SERVICE_TAX","ADJUSTMENT","SETTLEMENT_CHARGE",
            "SETTLEMENT_TAX","SPLIT_CHARGE","SPLIT_TAX","VENDOR_COMMISSION");
    private final UUID organisation=UUID.randomUUID(),school=UUID.randomUUID(),request=UUID.randomUUID(),
            preparation=UUID.randomUUID(),root=UUID.randomUUID(),bankEvidence=UUID.randomUUID(),client=UUID.randomUUID(),
            bank=UUID.randomUUID(),read=UUID.randomUUID(),year=UUID.randomUUID(),journal=UUID.randomUUID(),
            payment=UUID.randomUUID(),refundPosting=UUID.randomUUID(),clearing=UUID.randomUUID();
    private final Map<String,AuthenticatedUser> actors=new LinkedHashMap<>();
    private final ObjectMapper json=new ObjectMapper();
    private AnnotationConfigWebApplicationContext application;
    private MockMvc mvc;
    private GatewaySettlementPreparationService service;

    @BeforeEach void setup() {
        actors.put("reader",actor("fees.read","accounting.read"));
        actors.put("maker",actor("fees.read","accounting.read","fees.close.propose","accounting.prepare"));
        actors.put("fees-proposer",actor("fees.read","accounting.read","fees.close.propose"));
        actors.put("books-preparer",actor("fees.read","accounting.read","accounting.prepare"));
        actors.put("checker",actor("fees.read","accounting.read","fees.close.approve","accounting.post"));
        actors.put("fees-only",actor("fees.read"));
        actors.put("books-only",actor("accounting.read"));
        actors.put("prepare-only",actor("fees.close.propose","accounting.prepare"));
        actors.put("guardian",actor("portal.guardian"));
        application=new AnnotationConfigWebApplicationContext();application.setServletContext(new MockServletContext());
        application.register(Config.class);application.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(application).apply(springSecurity()).build();
        service=application.getBean(GatewaySettlementPreparationService.class);
        var sessions=application.getBean(SessionService.class);
        actors.forEach((name,user)->when(sessions.authenticate(token(name))).thenReturn(Optional.of(
                new SessionService.AuthenticatedSession(user,Instant.now().plusSeconds(3600)))));
        when(service.context(any(),any(),any(),anyInt(),anyInt())).thenAnswer(call->{
            AuthenticatedUser user=call.getArgument(0);assertTenant(user);return context(user,call.getArgument(3),call.getArgument(4));});
        when(service.detail(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return detail();});
        when(service.prepare(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return prepared("SUBMITTED");});
        when(service.abandon(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));
            return new GatewaySettlementPreparationModels.AbandonmentResult(client,prepared("ABANDONED"));});
    }

    @AfterEach void cleanup() {
        assertThat(TenantRequestContext.current()).isEmpty();SecurityContextHolder.clearContext();
        if(application!=null)application.close();
    }

    @Test void bothReadsDenyAnonymousRevokedGuardianAndEitherMissingReadCapability() throws Exception {
        for(String endpoint:List.of(contextPath(),detailPath()))
            for(String name:List.of("","revoked","guardian","fees-only","books-only","prepare-only")) {
                var call=get(endpoint).param("schoolId",school.toString());if(!name.isEmpty())call.cookie(auth(name));
                filterPrivate(mvc.perform(call).andExpect(status().is(actors.containsKey(name)?403:401)));
            }
        verifyNoInteractions(service);
    }

    @Test void contextUsesScopedPaginationAndKeepsAuthorityHintSeparateFromBlockers() throws Exception {
        var response=mvc.perform(get(contextPath()).cookie(auth("maker")).param("schoolId",school.toString())
                .param("page","2").param("pageSize","10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.schoolId").value(school.toString())).andExpect(jsonPath("$.requestId").value(request.toString()))
                .andExpect(jsonPath("$.contextFingerprint").value(FINGERPRINT)).andExpect(jsonPath("$.canPrepare").value(true))
                .andExpect(jsonPath("$.accountingOptions.canPrepare").value(true))
                .andExpect(jsonPath("$.accountingOptions.financialYears").isEmpty())
                .andExpect(jsonPath("$.blockers[0]").value("NO_OPEN_ACCOUNTING_YEAR"))
                .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.pageSize").value(10))
                .andExpect(jsonPath("$.rows[0].sourceReadId").value(read.toString()))
                .andExpect(jsonPath("$.rows[0].rowIndex").value(1))
                .andExpect(jsonPath("$.rows[0].status").value("REFUND_SELECTION_REQUIRED"))
                .andExpect(jsonPath("$.rows[0].source").isEmpty()).andExpect(jsonPath("$.canPost").doesNotExist());
        privateResponse(response);assertNoInfrastructure(response.andReturn().getResponse());
        verify(service).context(actors.get("maker"),school,request,2,10);
        verify(service,never()).prepare(any(),any(),any());verify(service,never()).abandon(any(),any(),any());
    }

    @Test void readerReceivesExactFrozenDetailWithPaymentAndRefundPhysicalProvenance() throws Exception {
        var response=mvc.perform(get(detailPath()).cookie(auth("reader")).param("schoolId",school.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.preparation.id").value(preparation.toString()))
                .andExpect(jsonPath("$.preparation.clientRequestId").value(client.toString()))
                .andExpect(jsonPath("$.preparation.rootId").value(root.toString()))
                .andExpect(jsonPath("$.preparation.journalId").value(journal.toString()))
                .andExpect(jsonPath("$.preparation.previousPreparationId").isEmpty())
                .andExpect(jsonPath("$.preparation.bankAmount").value("149.75"))
                .andExpect(jsonPath("$.componentBasis").value("SUMMARY"))
                .andExpect(jsonPath("$.componentFacts[0].eventsAmount").isEmpty())
                .andExpect(jsonPath("$.componentReviews[0].accountId").isEmpty())
                .andExpect(jsonPath("$.refundAssociations[0].refundPostingId").value(refundPosting.toString()))
                .andExpect(jsonPath("$.sources[0].kind").value("PAYMENT"))
                .andExpect(jsonPath("$.sources[0].paymentId").value(payment.toString()))
                .andExpect(jsonPath("$.sources[0].refundPostingId").isEmpty())
                .andExpect(jsonPath("$.sources[0].associationReason").isEmpty())
                .andExpect(jsonPath("$.sources[1].kind").value("REFUND"))
                .andExpect(jsonPath("$.sources[1].paymentId").value(payment.toString()))
                .andExpect(jsonPath("$.sources[1].refundPostingId").value(refundPosting.toString()))
                .andExpect(jsonPath("$.sources[1].sourceReadId").value(read.toString()))
                .andExpect(jsonPath("$.sources[1].rowIndex").value(1))
                .andExpect(jsonPath("$.sources[1].amount").value("50.25"))
                .andExpect(jsonPath("$.sources[1].accountingJournalId").value(journal.toString()))
                .andExpect(jsonPath("$.sources[1].associationReason").value("Synthetic explicit refund choice"));
        privateResponse(response);assertNoInfrastructure(response.andReturn().getResponse());
        verify(service).detail(actors.get("reader"),school,preparation);
        verify(service,never()).prepare(any(),any(),any());verify(service,never()).abandon(any(),any(),any());
    }

    @Test void bothCommandsRequireAllFourCapabilitiesEvenWithValidCsrf() throws Exception {
        var csrf=csrf();
        for(String endpoint:List.of(preparePath(),abandonPath()))
            for(String name:List.of("reader","checker","fees-proposer","books-preparer","fees-only","books-only","prepare-only","guardian")) {
                var response=mvc.perform(post(endpoint).cookie(auth(name),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                        .contentType("application/json").content(payload(endpoint))).andExpect(status().isForbidden());
                privateResponse(response);assertNoPrivateEcho(response.andReturn().getResponse());
            }
        verifyNoInteractions(service);
    }

    @Test void neitherCommandAcceptsAbsentOrRevokedSessionEvenWithValidCsrf() throws Exception {
        var csrf=csrf();
        for(String endpoint:List.of(preparePath(),abandonPath()))for(String name:List.of("","revoked")) {
            var call=post(endpoint).cookie(csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(payload(endpoint));
            if(!name.isEmpty())call.cookie(auth(name));filterPrivate(mvc.perform(call).andExpect(status().isUnauthorized()));
        }
        verifyNoInteractions(service);
    }

    @Test void bothCommandsRequireActualMatchingCsrfCookieAndHeader() throws Exception {
        var csrf=csrf();
        for(String endpoint:List.of(preparePath(),abandonPath())) {
            filterPrivate(mvc.perform(post(endpoint).cookie(auth("maker")).contentType("application/json").content(payload(endpoint))).andExpect(status().isForbidden()));
            filterPrivate(mvc.perform(post(endpoint).cookie(auth("maker"),csrf).contentType("application/json").content(payload(endpoint))).andExpect(status().isForbidden()));
            filterPrivate(mvc.perform(post(endpoint).cookie(auth("maker")).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(payload(endpoint))).andExpect(status().isForbidden()));
            filterPrivate(mvc.perform(post(endpoint).cookie(auth("maker"),csrf).header("X-CSRF-TOKEN","synthetic-mismatched-csrf").contentType("application/json").content(payload(endpoint))).andExpect(status().isForbidden()));
        }
        verifyNoInteractions(service);
    }

    @Test void validPreparationBindsExactReviewedTermsAndCurrentHumanWithoutPosting() throws Exception {
        var csrf=csrf();var response=mvc.perform(post(preparePath()).cookie(auth("maker"),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(body())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(preparation.toString())).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.journalId").value(journal.toString())).andExpect(jsonPath("$.canPost").doesNotExist());
        privateResponse(response);assertNoInfrastructure(response.andReturn().getResponse());
        var command=ArgumentCaptor.forClass(GatewaySettlementPreparationModels.Command.class);
        verify(service).prepare(eq(actors.get("maker")),eq(request),command.capture());
        assertThat(command.getValue()).isEqualTo(new GatewaySettlementPreparationModels.Command(school,client,FINGERPRINT,bankEvidence,
                year,LocalDate.of(2026,10,25),"INR",true,"SUMMARY",reviews(),associations(),null,true,PRIVATE_MARKER));
        verify(service,never()).abandon(any(),any(),any());
    }

    @Test void abandonmentBindsExplicitRejectedOnlyReleaseAndReturnsRetainedHistory() throws Exception {
        var csrf=csrf();var response=mvc.perform(post(abandonPath()).cookie(auth("maker"),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                .contentType("application/json").content(abandonmentBody())).andExpect(status().isOk())
                .andExpect(jsonPath("$.clientRequestId").value(client.toString()))
                .andExpect(jsonPath("$.preparation.id").value(preparation.toString()))
                .andExpect(jsonPath("$.preparation.status").value("ABANDONED"));
        privateResponse(response);assertNoInfrastructure(response.andReturn().getResponse());
        verify(service).abandon(actors.get("maker"),preparation,new GatewaySettlementPreparationModels.AbandonmentCommand(
                school,client,PRIVATE_MARKER,true));verify(service,never()).prepare(any(),any(),any());
    }

    @Test void invalidBoundedReviewsAndExplicitConfirmationsFailBeforeServiceWithoutPrivateEcho() throws Exception {
        var csrf=csrf();var invalid=new ArrayList<String>();
        invalid.add("{}");invalid.add("{\"reason\":\""+PRIVATE_MARKER);invalid.add(body().replace(FINGERPRINT,"A".repeat(64)));
        invalid.add(body().replace("\"INR\"","\"USD\""));invalid.add(body().replace("\"confirmedReviewed\":true","\"confirmedReviewed\":false"));
        invalid.add(body().replace("\"confirmedSummaryCurrency\":true","\"confirmedSummaryCurrency\":false"));
        invalid.add(body().replace("\"SUMMARY\"","\"INFERRED\""));invalid.add(body().replace("\"CONFIRMED_ZERO\"","\"AUTO_ZERO\""));
        invalid.add(body().replace("\"DEBIT\"","\"NET\""));invalid.add(body().replace("\"postedAmount\":\"0.00\"","\"postedAmount\":\"12345678901234567\""));
        invalid.add(body().replace("\"rowIndex\":1","\"rowIndex\":10"));invalid.add(body().replace(PRIVATE_MARKER,"x".repeat(241)));
        var tooFew=bodyMap();tooFew.put("componentReviews",reviews().subList(0,7));invalid.add(json.writeValueAsString(tooFew));
        for(String payload:invalid) {
            var response=mvc.perform(post(preparePath()).cookie(auth("maker"),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                    .contentType("application/json").content(payload)).andExpect(status().isBadRequest());
            privateResponse(response);assertNoPrivateEcho(response.andReturn().getResponse());
        }
        for(String payload:List.of("{}",abandonmentBody().replace("\"confirmedReleaseRejectedPreparation\":true","\"confirmedReleaseRejectedPreparation\":false"),
                abandonmentBody().replace(PRIVATE_MARKER,"x".repeat(241)))) {
            var response=mvc.perform(post(abandonPath()).cookie(auth("maker"),csrf).header("X-CSRF-TOKEN",csrf.getValue())
                    .contentType("application/json").content(payload)).andExpect(status().isBadRequest());
            privateResponse(response);assertNoPrivateEcho(response.andReturn().getResponse());
        }
        verifyNoInteractions(service);
    }

    @Test void malformedScopeAndPaginationArePrivate400BeforeService() throws Exception {
        var calls=List.of(get(contextPath()).cookie(auth("reader")),get(detailPath()).cookie(auth("reader")),
                scopedGet(contextPath()).param("page","-1"),scopedGet(contextPath()).param("page","10001"),
                scopedGet(contextPath()).param("pageSize","0"),scopedGet(contextPath()).param("pageSize","51"),
                get(contextPath()).cookie(auth("reader")).param("schoolId",PRIVATE_MARKER),
                scopedGet("/api/v1/finance/gateway/settlements/preparations/synthetic-invalid-id"));
        for(var call:calls) {var response=mvc.perform(call).andExpect(status().isBadRequest());
            privateResponse(response);assertNoPrivateEcho(response.andReturn().getResponse());}
        verifyNoInteractions(service);
    }

    @Test void untrustedOriginCannotReadPrepareOrReleaseWithOtherwiseValidSessionAndCsrf() throws Exception {
        var csrf=csrf();String origin="https://synthetic-untrusted-preparation.example.test";
        for(String endpoint:List.of(contextPath(),detailPath()))filterPrivate(mvc.perform(scopedGet(endpoint).header("Origin",origin)).andExpect(status().isForbidden()));
        for(String endpoint:List.of(preparePath(),abandonPath()))filterPrivate(mvc.perform(post(endpoint).cookie(auth("maker"),csrf)
                .header("X-CSRF-TOKEN",csrf.getValue()).header("Origin",origin).contentType("application/json").content(payload(endpoint))).andExpect(status().isForbidden()));
        verifyNoInteractions(service);
    }

    @Test void deleteCannotRemovePreparationFrozenClaimsOrAbandonmentHistory() throws Exception {
        var csrf=csrf();
        for(String endpoint:List.of(contextPath(),detailPath(),preparePath(),abandonPath())) {
            var response=mvc.perform(delete(endpoint).cookie(auth("maker"),csrf).header("X-CSRF-TOKEN",csrf.getValue()))
                    .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
            privateResponse(response);assertNoInfrastructure(response.andReturn().getResponse());
        }
        verifyNoInteractions(service);
    }

    @Test void liveScopeNotFoundAndReplayConflictsRemainControlledPrivateErrors() throws Exception {
        var csrf=csrf();
        for(HttpStatus status:List.of(HttpStatus.FORBIDDEN,HttpStatus.NOT_FOUND,HttpStatus.CONFLICT)) {
            String code=status==HttpStatus.FORBIDDEN?"GATEWAY_SETTLEMENT_SCOPE_DENIED":status==HttpStatus.NOT_FOUND?
                    "GATEWAY_SETTLEMENT_PREPARATION_NOT_FOUND":"GATEWAY_SETTLEMENT_REQUEST_REUSED";
            var failure=new ApiException(status,code,"Refresh the authorized synthetic preparation review.");
            doThrow(failure).when(service).context(any(),any(),any(),anyInt(),anyInt());doThrow(failure).when(service).detail(any(),any(),any());
            doThrow(failure).when(service).prepare(any(),any(),any());doThrow(failure).when(service).abandon(any(),any(),any());
            for(var response:List.of(mvc.perform(scopedGet(contextPath())),mvc.perform(scopedGet(detailPath())),
                    mvc.perform(post(preparePath()).cookie(auth("maker"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body())),
                    mvc.perform(post(abandonPath()).cookie(auth("maker"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(abandonmentBody())))) {
                response.andExpect(status().is(status.value())).andExpect(jsonPath("$.code").value(code));
                privateResponse(response);assertNoPrivateEcho(response.andReturn().getResponse());
            }
        }
    }

    private String contextPath(){return "/api/v1/finance/gateway/settlements/requests/"+request+"/preparation-context";}
    private String preparePath(){return "/api/v1/finance/gateway/settlements/requests/"+request+"/preparations";}
    private String detailPath(){return "/api/v1/finance/gateway/settlements/preparations/"+preparation;}
    private String abandonPath(){return detailPath()+"/abandonment";}
    private MockHttpServletRequestBuilder scopedGet(String endpoint){return get(endpoint).cookie(auth("reader")).param("schoolId",school.toString());}
    private static String token(String name){return "synthetic-preparation-"+name+"-session-00001";}
    private static Cookie auth(String name){return new Cookie("educore_session",token(name));}
    private Cookie csrf() throws Exception {
        var response=mvc.perform(scopedGet(contextPath())).andExpect(status().isOk()).andReturn().getResponse();
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
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_MARKER,token("reader"),token("maker"),"HttpMessageNotReadableException");
    }
    private static void assertNoInfrastructure(MockHttpServletResponse response) throws Exception {
        assertThat(response.getContentAsString()).doesNotContain("credentials","merchantAccountReference","clientSecret","evidenceSnapshot","evidenceSha256","canPost");
    }
    private AuthenticatedUser actor(String...capabilities){return new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),
            "synthetic-preparation@example.test",null,"Synthetic preparation actor",true,organisation,Set.of(UUID.randomUUID()),Set.of(capabilities),List.of());}
    private static void assertTenant(AuthenticatedUser user){var scope=TenantRequestContext.current().orElseThrow();
        assertThat(scope.userId()).isEqualTo(user.userId());assertThat(scope.organisationId()).isEqualTo(user.activeOrganisationId());}
    private List<GatewaySettlementPreparationModels.ComponentReview> reviews(){return KEYS.stream().map(key->
            new GatewaySettlementPreparationModels.ComponentReview(key,"CONFIRMED_ZERO","DEBIT",null,"0.00",null,"Synthetic reported zero review")).toList();}
    private List<GatewaySettlementPreparationModels.ComponentFact> facts(){return KEYS.stream().map(key->
            new GatewaySettlementPreparationModels.ComponentFact(key,"0.00000000",null,false)).toList();}
    private List<GatewaySettlementPreparationModels.RefundAssociation> associations(){return List.of(
            new GatewaySettlementPreparationModels.RefundAssociation(read,1,refundPosting,"Synthetic explicit refund choice"));}
    private Map<String,Object> bodyMap(){var values=new LinkedHashMap<String,Object>();
        values.put("schoolId",school);values.put("clientRequestId",client);values.put("contextFingerprint",FINGERPRINT);values.put("bankEvidenceId",bankEvidence);
        values.put("financialYearId",year);values.put("postingOn","2026-10-25");values.put("summaryCurrencyCode","INR");values.put("confirmedSummaryCurrency",true);
        values.put("componentBasis","SUMMARY");values.put("componentReviews",reviews());values.put("refundAssociations",associations());
        values.put("previousPreparationId",null);values.put("confirmedReviewed",true);values.put("reason",PRIVATE_MARKER);return values;}
    private String body(){return json.writeValueAsString(bodyMap());}
    private String abandonmentBody(){return json.writeValueAsString(Map.of("schoolId",school,"clientRequestId",client,"reason",PRIVATE_MARKER,"confirmedReleaseRejectedPreparation",true));}
    private String payload(String endpoint){return endpoint.equals(preparePath())?body():abandonmentBody();}
    private GatewaySettlementModels.Summary summary(){return new GatewaySettlementModels.Summary("synthetic_statement","SUCCESS","synthetic_transfer",null,null,null,AT,
            "200.00","0.00000000","0.00000000","0.00000000","0.00000000","0.00000000","0.00000000","0.00000000","0.00000000","149.75",null,null);}
    private GatewaySettlementBankEvidenceModels.Evidence evidence(){return new GatewaySettlementBankEvidenceModels.Evidence(bankEvidence,UUID.randomUUID(),bank,
            PRIVATE_MARKER,"INR","149.75",AT.toString(),LocalDate.of(2026,10,25),"BANK_STATEMENT_CREDIT",AT,"Synthetic bank maker",
            "Synthetic actual bank credit review","BANK_EVIDENCE_RECORDED",List.of("NOT_RECONCILED","SUMMARY_CURRENCY_UNCONFIRMED","SOURCE_RECONCILIATION_PENDING"),null,false);}
    private GatewaySettlementPreparationModels.Preparation prepared(String status){return new GatewaySettlementPreparationModels.Preparation(preparation,client,root,request,
            bankEvidence,1,null,journal,status,1,"SYNTHETIC-JV-1",LocalDate.of(2026,10,25),"INR","149.75","200.00","50.25","0.00","0.00",2,AT,
            "Synthetic preparation maker","Synthetic complete statement review");}
    private GatewaySettlementPreparationModels.Context context(AuthenticatedUser user,int page,int pageSize){
        boolean canPrepare=user.capabilities().containsAll(Set.of("fees.close.propose","accounting.prepare"));
        var event=new GatewaySettlementModels.Event("synthetic_statement","synthetic_refund_event","REFUND","DEBIT","SUCCESS","INR","50.25",null,null,null,AT,
                "synthetic_order","synthetic_payment",List.of("synthetic_refund"),null);
        return new GatewaySettlementPreparationModels.Context(school,request,"synthetic_statement","Asia/Kolkata",FINGERPRINT,canPrepare,
                new SettlementAccountingOptions(canPrepare,List.of(),List.of()),List.of(evidence()),summary(),facts(),"200.00","50.25",page,pageSize,2,1,false,
                List.of(new GatewaySettlementPreparationModels.Row(read,1,event,"REFUND_SELECTION_REQUIRED",List.of("REFUND_ASSOCIATION_REVIEW"),null)),
                List.of("NO_OPEN_ACCOUNTING_YEAR"),List.of(prepared("SUBMITTED")));}
    private GatewaySettlementPreparationModels.PreparationDetail detail(){return new GatewaySettlementPreparationModels.PreparationDetail(prepared("SUBMITTED"),
            "Asia/Kolkata",summary(),evidence(),"SUMMARY",facts(),reviews(),associations(),List.of(
            new GatewaySettlementPreparationModels.SourceIdentity("PAYMENT",payment,null,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),journal,clearing,"200.00",read,0,null),
            new GatewaySettlementPreparationModels.SourceIdentity("REFUND",payment,refundPosting,UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),journal,clearing,"50.25",read,1,"Synthetic explicit refund choice")));}

    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config {
        @Bean GatewaySettlementPreparationService service(){return mock(GatewaySettlementPreparationService.class);}
        @Bean GatewaySettlementPreparationController controller(GatewaySettlementPreparationService service){return new GatewaySettlementPreparationController(service);}
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
