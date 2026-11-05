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
import java.time.Clock;
import java.time.Instant;
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

/** Actual HTTP session/CSRF/CORS/method validation. Mock domain service, no DB/provider or browser claims. */
class GatewaySettlementSecurityTest {
    private static final String BASE="/api/v1/finance/gateway/settlements";
    private final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),requestId=UUID.randomUUID(),client=UUID.randomUUID();
    private final Map<String,AuthenticatedUser> actors=new LinkedHashMap<>();
    private AnnotationConfigWebApplicationContext context;private MockMvc mvc;private GatewaySettlementService service;
    private GatewaySettlementSourceReviewService sources;
    private GatewaySettlementRefundCandidateService refundCandidates;
    @BeforeEach void setup(){
        actors.put("synthetic-settlement-reader-session-00001",actor("fees.read","accounting.read"));
        actors.put("synthetic-settlement-writer-session-00001",actor("fees.read","accounting.read","fees.close.propose"));
        actors.put("synthetic-settlement-fees-only-session-01",actor("fees.read"));
        actors.put("synthetic-settlement-books-only-session-1",actor("accounting.read"));
        actors.put("synthetic-settlement-parent-session-0001",actor("portal.guardian"));
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();service=context.getBean(GatewaySettlementService.class);
        sources=context.getBean(GatewaySettlementSourceReviewService.class);
        refundCandidates=context.getBean(GatewaySettlementRefundCandidateService.class);
        var sessions=context.getBean(SessionService.class);actors.forEach((token,user)->when(sessions.authenticate(token)).thenReturn(Optional.of(new SessionService.AuthenticatedSession(user,Instant.now().plusSeconds(300)))));
        when(service.queue(any(),any(),anyString(),anyString(),nullable(String.class),anyInt(),anyInt())).thenAnswer(call->{assertTenant(call.getArgument(0));return new GatewaySettlementModels.Queue(school,"Asia/Kolkata",false,false,"ALL","ALL","",0,25,0,false,List.of());});
        when(service.submit(any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new GatewaySettlementModels.Result(requestId,school,"SUMMARY");});
        when(sources.review(any(),any(),any(),anyInt(),anyInt())).thenAnswer(call->{
            assertTenant(call.getArgument(0));
            return new GatewaySettlementSourceReviewModels.Review(school,requestId,"synthetic_statement","SOURCE_IDENTIFICATION_ONLY",
                    1,0,0,0,call.getArgument(3),call.getArgument(4),false,List.of());
        });
    }
    @AfterEach void close(){assertThat(TenantRequestContext.current()).isEmpty();SecurityContextHolder.clearContext();if(context!=null)context.close();}
    @Test void anonymousRevokedGuardianAndEitherSingleReadAuthorityCannotInspectReports()throws Exception{
        for(String token:List.of("","synthetic-settlement-revoked-session-001",token("fees-only"),token("books-only"),token("parent"))){
            for(String path:List.of(BASE,BASE+"/requests/"+requestId)){
                var call=get(path).param("schoolId",school.toString());if(!token.isEmpty())call.cookie(cookie(token));
                mvc.perform(call).andExpect(status().is(actors.containsKey(token)?403:401));
            }
        }verifyNoInteractions(service);
    }
    @Test void bothReadAuthoritiesReceivePrivateNoStoreScopedProjectionWithoutAnyActivation()throws Exception{
        var response=mvc.perform(get(BASE).cookie(cookie(token("reader"))).param("schoolId",school.toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.schoolId").value(school.toString())).andExpect(jsonPath("$.syncEnabled").value(false)).andExpect(jsonPath("$.canSync").value(false)).andExpect(jsonPath("$.query").value("")).andReturn().getResponse();
        assertThat(response.getHeaders("Vary")).contains("Cookie");assertThat(response.getContentAsString()).doesNotContain("credentials","merchant","cursor");
        verify(service).queue(actors.get(token("reader")),school,"ALL","ALL",null,0,25);
    }
    @Test void combinedReaderStillCannotRequestSyncWithOtherwiseValidCsrf()throws Exception{
        var csrf=csrf();for(String token:List.of(token("reader"),token("fees-only"),token("books-only"),token("parent")))
            mvc.perform(post(BASE+"/requests").cookie(cookie(token),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void missingCookieHeaderOnlyCookieOnlyAndMismatchedCsrfCannotRequestSync()throws Exception{
        var csrf=csrf();var path=BASE+"/requests";var auth=cookie(token("writer"));
        mvc.perform(post(path).cookie(auth).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(auth,csrf).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(auth).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body())).andExpect(status().isForbidden());
        mvc.perform(post(path).cookie(auth,csrf).header("X-CSRF-TOKEN","synthetic-wrong").contentType("application/json").content(body())).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void actualSessionAndCsrfBindExactHumanIntentWithoutBrowserProviderBinding()throws Exception{
        var csrf=csrf();mvc.perform(post(BASE+"/requests").cookie(cookie(token("writer")),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store")).andExpect(jsonPath("$.id").value(requestId.toString()));
        verify(service).submit(actors.get(token("writer")),new GatewaySettlementModels.Submit(school,client,"Asia/Kolkata","SUMMARY",Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-01-02T00:00:00Z"),null,null));
    }
    @Test void invalidBodiesAndUnboundedHttpFiltersAreRejectedBeforeServiceWithoutPrivateEcho()throws Exception{
        var csrf=csrf();for(String body:List.of("{}",body().replace("SUMMARY","SUCCESS"),body().replace("Asia/Kolkata","x".repeat(61))))
            mvc.perform(post(BASE+"/requests").cookie(cookie(token("writer")),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        for(var invalid:List.of(new String[]{"status","SUCCESS"},new String[]{"kind","PAYMENT"},new String[]{"page","10001"},new String[]{"pageSize","51"},new String[]{"q","synthetic-private-search".repeat(6)})){
            String response=mvc.perform(get(BASE).cookie(cookie(token("reader"))).param("schoolId",school.toString()).param(invalid[0],invalid[1])).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("synthetic-private-search",token("reader"));
        }verifyNoInteractions(service);
    }
    @Test void untrustedOriginCannotReadOrWriteWithOtherwiseValidSessionAndCsrf()throws Exception{
        var csrf=csrf();mvc.perform(get(BASE).cookie(cookie(token("reader"))).param("schoolId",school.toString()).header("Origin","https://synthetic-untrusted.example.test")).andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/requests").cookie(cookie(token("writer")),csrf).header("X-CSRF-TOKEN",csrf.getValue()).header("Origin","https://synthetic-untrusted.example.test").contentType("application/json").content(body())).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void liveScopeDenialAndChangedRequestConflictStayControlledAndPrivate()throws Exception{
        var csrf=csrf();doThrow(new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_SCOPE_REQUIRED","Current school authority required")).when(service).submit(any(),any());
        mvc.perform(post(BASE+"/requests").cookie(cookie(token("writer")),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body())).andExpect(status().isForbidden());
        doThrow(new ApiException(HttpStatus.CONFLICT,"GATEWAY_SETTLEMENT_REQUEST_REUSED","Refresh exact original request")).when(service).submit(any(),any());
        mvc.perform(post(BASE+"/requests").cookie(cookie(token("writer")),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GATEWAY_SETTLEMENT_REQUEST_REUSED"));
    }
    @Test void sourceReviewRequiresActualSessionAndBothLiveReadAuthorities()throws Exception{
        String path=BASE+"/requests/"+requestId+"/source-review";
        for(String token:List.of("","synthetic-settlement-revoked-session-001",token("fees-only"),token("books-only"),token("parent"))){
            var call=get(path).param("schoolId",school.toString());if(!token.isEmpty())call.cookie(cookie(token));
            mvc.perform(call).andExpect(status().is(actors.containsKey(token)?403:401));
        }
        verifyNoInteractions(sources);
    }
    @Test void sourceReviewGetIsPrivateHistoricalEvidenceNotBankOrPostingAuthority()throws Exception{
        var response=mvc.perform(get(BASE+"/requests/"+requestId+"/source-review").cookie(cookie(token("reader")))
                .param("schoolId",school.toString()).param("page","2").param("pageSize","10"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andExpect(jsonPath("$.reviewStage").value("SOURCE_IDENTIFICATION_ONLY"))
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.bankConfirmed").doesNotExist()).andExpect(jsonPath("$.canPost").doesNotExist())
                .andReturn().getResponse();
        assertThat(response.getHeaders("Vary")).contains("Cookie");
        verify(sources).review(actors.get(token("reader")),school,requestId,2,10);
        verifyNoInteractions(service);
    }
    @Test void sourceReviewRejectsInvalidPagingUntrustedOriginAndMutationMethodsBeforeDomainService()throws Exception{
        String path=BASE+"/requests/"+requestId+"/source-review";
        for(var invalid:List.of(new String[]{"page","-1"},new String[]{"page","10001"},new String[]{"pageSize","0"},new String[]{"pageSize","51"}))
            mvc.perform(get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString()).param(invalid[0],invalid[1])).andExpect(status().isBadRequest());
        mvc.perform(get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString()).header("Origin","https://synthetic-untrusted.example.test")).andExpect(status().isForbidden());
        var csrf=csrf();
        mvc.perform(post(path).cookie(cookie(token("writer")),csrf).header("X-CSRF-TOKEN",csrf.getValue()).param("schoolId",school.toString())).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(sources);
    }
    @Test void sourceReviewLiveScopeAndUnavailableChainRemainPrivateErrors()throws Exception{
        String path=BASE+"/requests/"+requestId+"/source-review";
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_SCOPE_DENIED","Current school authority required")).when(sources).review(any(),any(),any(),anyInt(),anyInt());
        mvc.perform(get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString())).andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","private, no-store"));
        doThrow(new ApiException(HttpStatus.CONFLICT,"GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE","Complete historical event evidence required")).when(sources).review(any(),any(),any(),anyInt(),anyInt());
        mvc.perform(get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE")).andExpect(header().string("Cache-Control","private, no-store"));
    }
    private Cookie csrf()throws Exception{var csrf=mvc.perform(get(BASE).cookie(cookie(token("reader"))).param("schoolId",school.toString())).andExpect(status().isOk()).andReturn().getResponse().getCookie("educore_csrf");assertThat(csrf).isNotNull();clearInvocations(service);return csrf;}
    @Test void refundCandidatesRequireActualSessionBothReadAuthoritiesAndPrivateScopedProjection()throws Exception{
        String path=BASE+"/requests/"+requestId+"/refund-candidates";UUID read=UUID.randomUUID();
        for(String token:List.of("","synthetic-settlement-revoked-session-001",token("fees-only"),token("books-only"),token("parent"))){
            var call=get(path).param("schoolId",school.toString()).param("sourceReadId",read.toString()).param("rowIndex","0");
            if(!token.isEmpty())call.cookie(cookie(token));mvc.perform(call).andExpect(status().is(actors.containsKey(token)?403:401));
        }
        verifyNoInteractions(refundCandidates);
        when(refundCandidates.candidates(any(),any(),any(),any(),anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            assertTenant(call.getArgument(0));return new GatewaySettlementRefundCandidateModels.Review(school,requestId,read,0,"synthetic_statement",
                    "HISTORICAL_REFUND_CANDIDATES_ONLY","CANDIDATE_CONTEXT","synthetic_event","50.25000000","INR","SYNTHETIC-R-001",List.of(),0,25,false,List.of());
        });
        mvc.perform(get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString()).param("sourceReadId",read.toString()).param("rowIndex","0"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(jsonPath("$.sourceReadId").value(read.toString()))
                .andExpect(jsonPath("$.reviewStage").value("HISTORICAL_REFUND_CANDIDATES_ONLY")).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.canPost").doesNotExist()).andExpect(jsonPath("$.bankConfirmed").doesNotExist());
        verify(refundCandidates).candidates(actors.get(token("reader")),school,requestId,read,0,0,25);
    }
    @Test void refundCandidatesRejectInvalidHttpScopePagingOriginsAndMutationBeforeService()throws Exception{
        String path=BASE+"/requests/"+requestId+"/refund-candidates";UUID read=UUID.randomUUID();
        for(var invalid:List.of(new String[]{"rowIndex","-1"},new String[]{"rowIndex","10"},new String[]{"rowIndex","abc"},new String[]{"sourceReadId","synthetic-invalid"},new String[]{"page","10001"},new String[]{"pageSize","0"},new String[]{"pageSize","51"})){
            var call=get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString());
            if(!invalid[0].equals("sourceReadId"))call.param("sourceReadId",read.toString());if(!invalid[0].equals("rowIndex"))call.param("rowIndex","0");
            mvc.perform(call.param(invalid[0],invalid[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString())).andExpect(status().isBadRequest());
        mvc.perform(get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString()).param("sourceReadId",read.toString()).param("rowIndex","0").header("Origin","https://synthetic-untrusted.example.test")).andExpect(status().isForbidden());
        var csrf=csrf();mvc.perform(post(path).cookie(cookie(token("writer")),csrf).header("X-CSRF-TOKEN",csrf.getValue())).andExpect(status().isMethodNotAllowed());verifyNoInteractions(refundCandidates);
    }
    @Test void refundCandidatesPreserveLiveScopeAndIncompleteChainErrors()throws Exception{
        String path=BASE+"/requests/"+requestId+"/refund-candidates";
        for(var failure:List.of(new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_SETTLEMENT_SCOPE_DENIED","Synthetic live denial"),new ApiException(HttpStatus.CONFLICT,"GATEWAY_SETTLEMENT_EVIDENCE_UNAVAILABLE","Synthetic incomplete chain"))){
            doThrow(failure).when(refundCandidates).candidates(any(),any(),any(),any(),anyInt(),anyInt(),anyInt());
            mvc.perform(get(path).cookie(cookie(token("reader"))).param("schoolId",school.toString()).param("sourceReadId",UUID.randomUUID().toString()).param("rowIndex","0"))
                    .andExpect(status().is(failure.status().value())).andExpect(header().string("Cache-Control","private, no-store")).andExpect(jsonPath("$.code").value(failure.code()));
        }
    }
    private String token(String part){return actors.keySet().stream().filter(value->value.contains("-"+part+"-")).findFirst().orElseThrow();}
    private static Cookie cookie(String value){return new Cookie("educore_session",value);}
    private String body(){return "{\"schoolId\":\""+school+"\",\"clientRequestId\":\""+client+"\",\"schoolTimeZone\":\"Asia/Kolkata\",\"kind\":\"SUMMARY\",\"from\":\"2026-01-01T00:00:00Z\",\"until\":\"2026-01-02T00:00:00Z\",\"sourceSummaryReadId\":null,\"settlementReference\":null}";}
    private AuthenticatedUser actor(String...caps){return new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic@example.test",null,"Synthetic settlement actor",true,org,Set.of(UUID.randomUUID()),Set.of(caps),List.of());}
    private static void assertTenant(AuthenticatedUser user){var value=TenantRequestContext.current().orElseThrow();assertThat(value.userId()).isEqualTo(user.userId());assertThat(value.organisationId()).isEqualTo(user.activeOrganisationId());}
    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config{
        @Bean GatewaySettlementService settlements(){return mock(GatewaySettlementService.class);}
        @Bean GatewaySettlementController controller(GatewaySettlementService service){return new GatewaySettlementController(service);}
        @Bean GatewaySettlementSourceReviewService sourceReviews(){return mock(GatewaySettlementSourceReviewService.class);}
        @Bean GatewaySettlementRefundCandidateService refundCandidates(){return mock(GatewaySettlementRefundCandidateService.class);}
        @Bean GatewaySettlementSourceReviewController sourceController(GatewaySettlementSourceReviewService service,GatewaySettlementRefundCandidateService refunds){return new GatewaySettlementSourceReviewController(service,refunds);}
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean GlobalExceptionHandler advice(Clock clock){return new GlobalExceptionHandler(clock);}
        @Bean ApiSecurityErrorWriter errors(Clock clock){return new ApiSecurityErrorWriter(new ObjectMapper(),clock);}
        @Bean SessionService sessions(){return mock(SessionService.class);}
        @Bean PrivilegedMfaPolicy mfa(){return mock(PrivilegedMfaPolicy.class);}
        @Bean ApplicationProperties properties(){var properties=mock(ApplicationProperties.class);var security=mock(ApplicationProperties.Security.class);when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));when(properties.security()).thenReturn(security);when(security.sessionCookieName()).thenReturn("educore_session");return properties;}
        @Bean SessionAuthenticationFilter filter(SessionService sessions,ApplicationProperties properties,PrivilegedMfaPolicy mfa,ApiSecurityErrorWriter errors){return new SessionAuthenticationFilter(sessions,properties,mfa,errors);}
    }
}
