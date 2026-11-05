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
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import tools.jackson.databind.ObjectMapper;

/** Actual session/CSRF/CORS/controller gates; no synthetic csrf() shortcut or provider. */
class GatewayRefundSecurityTest {
    private static final String QUEUE="/api/v1/finance/gateway/refunds";
    private static final String READ="synthetic-refund-read-session-000000001",WRITE="synthetic-refund-write-session-00000001",PARENT="synthetic-refund-parent-session-0000001";
    private final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),payment=UUID.randomUUID(),refund=UUID.randomUUID(),charge=UUID.randomUUID(),clientId=UUID.randomUUID();
    private final AuthenticatedUser reader=actor("fees.read"),reviewer=actor("fees.refund"),parent=actor("portal.guardian");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;private GatewayRefundService service;
    @BeforeEach void setup(){
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();service=context.getBean(GatewayRefundService.class);
        var sessions=context.getBean(SessionService.class);
        when(sessions.authenticate(READ)).thenReturn(session(reader));when(sessions.authenticate(WRITE)).thenReturn(session(reviewer));when(sessions.authenticate(PARENT)).thenReturn(session(parent));
        when(service.queue(any(),any(),anyString(),nullable(String.class),anyInt(),anyInt())).thenAnswer(call->{
            assertTenant(call.getArgument(0));return new GatewayRefundModels.Queue(school,call.getArgument(2),null,call.getArgument(4),call.getArgument(5),0,false,false,List.of());});
        when(service.payment(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new GatewayRefundModels.Payment(school,payment,"SYN-000001",UUID.randomUUID(),"Synthetic learner","INR",new BigDecimal("29.29"),BigDecimal.ZERO,new BigDecimal("29.29"),false,false,List.of());});
        when(service.submit(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new GatewayRefundModels.Result(refund,payment,"SUBMITTED");});
        when(service.decide(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new GatewayRefundModels.Result(refund,payment,"APPROVED");});
    }
    @AfterEach void close(){assertThat(TenantRequestContext.current()).isEmpty();SecurityContextHolder.clearContext();if(context!=null)context.close();}
    @Test void anonymousRevokedAndGuardianSessionsCannotReadRefundRecords()throws Exception{
        for(String token:List.of("",PARENT,"synthetic-refund-revoked-session-0000001"))for(String path:List.of(QUEUE,paymentPath())){
            var request=get(path).param("schoolId",school.toString());if(!token.isEmpty())request.cookie(cookie(token));
            mvc.perform(request).andExpect(status().is(token.equals(PARENT)?403:401));
        }
        verifyNoInteractions(service);
    }
    @Test void actualReadSessionReturnsMinimalPrivateScopedSnapshotsWithoutClaimingExecution()throws Exception{
        var terms=List.of(new GatewayRefundModels.Allocation(charge,"Synthetic original fee",new BigDecimal("29.29")));
        var intent=new GatewayRefundModels.Request(refund,payment,"SYN-000001","Synthetic learner",new BigDecimal("29.29"),"INR",
                "SUBMITTED","Synthetic refund reason",Instant.parse("2026-10-17T00:00:00Z"),"Synthetic maker",true,false,true,terms,null);
        doAnswer(call->{assertTenant(call.getArgument(0));return new GatewayRefundModels.Queue(school,call.getArgument(2),null,call.getArgument(4),call.getArgument(5),1,false,false,List.of(intent));})
                .when(service).queue(any(),any(),anyString(),nullable(String.class),anyInt(),anyInt());
        for(String path:List.of(QUEUE,paymentPath())){
            var response=mvc.perform(get(path).cookie(cookie(READ)).param("schoolId",school.toString()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                    .andExpect(header().string("X-Content-Type-Options","nosniff"))
                    .andExpect(jsonPath("$.schoolId").value(school.toString())).andExpect(jsonPath("$.executionEnabled").value(false)).andReturn().getResponse();
            assertThat(response.getHeaders("Vary")).contains("Cookie");
            assertThat(response.getContentAsString()).doesNotContain("credentials","merchant_account_reference","order_reference","payment_reference","payerPhone");
            if(path.equals(QUEUE)) {
                mvc.perform(get(path).cookie(cookie(READ)).param("schoolId",school.toString()))
                        .andExpect(jsonPath("$.items[0].isMaker").value(true)).andExpect(jsonPath("$.items[0].canReview").value(false))
                        .andExpect(jsonPath("$.items[0].canCancel").value(true)).andExpect(jsonPath("$.items[0].allocations[0].amount").value(29.29))
                        .andExpect(jsonPath("$.items[0].status").value("SUBMITTED"));
            }
        }
        verify(service,times(2)).queue(eq(reader),eq(school),eq("SUBMITTED"),isNull(),eq(0),eq(25));verify(service).payment(reader,school,payment);
    }
    @Test void readAndParentCapabilitiesCannotSubmitOrReviewEvenWithRealCsrf()throws Exception{
        Cookie csrf=csrf();
        for(String token:List.of(READ,PARENT))for(String path:List.of(paymentPath(),decisionPath()))
            mvc.perform(post(path).cookie(cookie(token),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(path.equals(paymentPath())?submitBody():decisionBody()))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void missingHeaderCookieOnlyHeaderOnlyAndMismatchedCsrfCannotWrite()throws Exception{
        Cookie csrf=csrf();
        for(String path:List.of(paymentPath(),decisionPath())){
            String body=path.equals(paymentPath())?submitBody():decisionBody();
            mvc.perform(post(path).cookie(cookie(WRITE)).contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(WRITE),csrf).contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(WRITE)).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN","synthetic-wrong-csrf").contentType("application/json").content(body)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void actualRefundSessionAndCsrfBindExactImmutableCommandsNotBrowserSuccess()throws Exception{
        Cookie csrf=csrf();
        mvc.perform(post(paymentPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(submitBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(refund.toString())).andExpect(jsonPath("$.status").value("SUBMITTED"));
        verify(service).submit(reviewer,payment,new GatewayRefundModels.Submit(school,clientId,List.of(new GatewayRefundModels.Selection(charge,new BigDecimal("29.29"))),"Synthetic refund reason"));
        mvc.perform(post(decisionPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(decisionBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        verify(service).decide(reviewer,refund,new GatewayRefundModels.DecisionCommand(school,"APPROVE","Synthetic independent review"));
    }
    @Test void invalidBodyAndUnboundedQueriesFailBeforeServiceAndNeverEchoPrivateInput()throws Exception{
        Cookie csrf=csrf();
        for(String body:List.of("{}",submitBody().replace("29.29","0"),submitBody().replace("29.29","1.001"),submitBody().replace("Synthetic refund reason","x".repeat(501))))
            mvc.perform(post(paymentPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(decisionPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(decisionBody().replace("APPROVE","SUCCESS"))).andExpect(status().isBadRequest());
        for(var invalid:List.of(new String[]{"page","10001"},new String[]{"pageSize","51"},new String[]{"status","SUCCESS"},new String[]{"q","synthetic-private-search".repeat(6)})){
            String body=mvc.perform(get(QUEUE).cookie(cookie(READ)).param("schoolId",school.toString()).param(invalid[0],invalid[1]))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("synthetic-private-search",READ);
        }
        mvc.perform(get(paymentPath()).cookie(cookie(READ))).andExpect(status().isBadRequest());verifyNoInteractions(service);
    }
    @Test void untrustedOriginCannotReadOrWriteWithOtherwiseValidCredentials()throws Exception{
        Cookie csrf=csrf();
        mvc.perform(get(QUEUE).cookie(cookie(READ)).param("schoolId",school.toString()).header("Origin","https://synthetic-untrusted.example.test")).andExpect(status().isForbidden());
        mvc.perform(post(paymentPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).header("Origin","https://synthetic-untrusted.example.test").contentType("application/json").content(submitBody())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void serviceAuthorityAndConflictingDecisionErrorsRemainControlledAndPrivate()throws Exception{
        Cookie csrf=csrf();
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"GATEWAY_REFUND_INDEPENDENT_REVIEW_REQUIRED","A different authorized person must review.")).when(service).decide(any(),any(),any());
        mvc.perform(post(decisionPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(decisionBody()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GATEWAY_REFUND_INDEPENDENT_REVIEW_REQUIRED"));
        doThrow(new ApiException(HttpStatus.CONFLICT,"GATEWAY_REFUND_ALREADY_REVIEWED","Refresh the immutable history.")).when(service).decide(any(),any(),any());
        mvc.perform(post(decisionPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(decisionBody()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GATEWAY_REFUND_ALREADY_REVIEWED"));
    }
    private Cookie csrf()throws Exception{Cookie value=mvc.perform(get(QUEUE).cookie(cookie(READ)).param("schoolId",school.toString())).andExpect(status().isOk()).andReturn().getResponse().getCookie("educore_csrf");assertThat(value).isNotNull();clearInvocations(service);return value;}
    private Cookie cookie(String value){return new Cookie("educore_session",value);}
    private String paymentPath(){return "/api/v1/finance/gateway/payments/"+payment+"/refunds";}
    private String decisionPath(){return QUEUE+"/"+refund+"/decision";}
    private String submitBody(){return "{\"schoolId\":\""+school+"\",\"clientRequestId\":\""+clientId+"\",\"allocations\":[{\"chargeId\":\""+charge+"\",\"amount\":29.29}],\"reason\":\"Synthetic refund reason\"}";}
    private String decisionBody(){return "{\"schoolId\":\""+school+"\",\"decision\":\"APPROVE\",\"reason\":\"Synthetic independent review\"}";}
    private AuthenticatedUser actor(String capability){return new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic-refund",null,"Synthetic reviewer",true,org,Set.of(UUID.randomUUID()),Set.of(capability),List.of());}
    private Optional<SessionService.AuthenticatedSession> session(AuthenticatedUser user){return Optional.of(new SessionService.AuthenticatedSession(user,Instant.now().plusSeconds(60)));}
    private void assertTenant(AuthenticatedUser user){var value=TenantRequestContext.current().orElseThrow();assertThat(value.userId()).isEqualTo(user.userId());assertThat(value.organisationId()).isEqualTo(user.activeOrganisationId());}
    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config{
        @Bean GatewayRefundService refunds(){return mock(GatewayRefundService.class);}
        @Bean GatewayRefundController controller(GatewayRefundService service){return new GatewayRefundController(service);}
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean GlobalExceptionHandler advice(Clock clock){return new GlobalExceptionHandler(clock);}
        @Bean ApiSecurityErrorWriter errors(Clock clock){return new ApiSecurityErrorWriter(new ObjectMapper(),clock);}
        @Bean SessionService sessions(){return mock(SessionService.class);}
        @Bean PrivilegedMfaPolicy mfa(){return mock(PrivilegedMfaPolicy.class);}
        @Bean ApplicationProperties properties(){var properties=mock(ApplicationProperties.class);var security=mock(ApplicationProperties.Security.class);when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));when(properties.security()).thenReturn(security);when(security.sessionCookieName()).thenReturn("educore_session");return properties;}
        @Bean SessionAuthenticationFilter filter(SessionService sessions,ApplicationProperties properties,PrivilegedMfaPolicy mfa,ApiSecurityErrorWriter errors){return new SessionAuthenticationFilter(sessions,properties,mfa,errors);}
    }
}
