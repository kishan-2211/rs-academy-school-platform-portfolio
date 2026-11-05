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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import tools.jackson.databind.ObjectMapper;

/** Real session/CSRF/controller gates. No provider or financial completion claim. */
class RefundAccountReopenSecurityTest {
    private static final String ROOT="/api/v1/finance/gateway", QUEUE=ROOT+"/refund-account-reopenings";
    private static final String READ="synthetic-reopen-read-session-00000001", WRITE="synthetic-reopen-write-session-0000001", PARENT="synthetic-reopen-parent-session-000001";
    private final UUID org=UUID.randomUUID(), school=UUID.randomUUID(), refund=UUID.randomUUID(), review=UUID.randomUUID(), account=UUID.randomUUID(), payment=UUID.randomUUID(), clientId=UUID.randomUUID();
    private final AuthenticatedUser reader=actor("fees.read"), reviewer=actor("fees.refund"), parent=actor("portal.guardian");
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc; private RefundAccountReopenService service;

    @BeforeEach void setup() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();service=context.getBean(RefundAccountReopenService.class);
        var sessions=context.getBean(SessionService.class);
        when(sessions.authenticate(READ)).thenReturn(session(reader));when(sessions.authenticate(WRITE)).thenReturn(session(reviewer));when(sessions.authenticate(PARENT)).thenReturn(session(parent));
        when(service.queue(any(),any(),anyString(),nullable(String.class),anyInt(),anyInt())).thenAnswer(call->{assertTenant(call.getArgument(0));return new RefundAccountReopenModels.Queue(school,call.getArgument(2),null,call.getArgument(4),call.getArgument(5),0,false,false,List.of());});
        when(service.context(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new RefundAccountReopenModels.Context(school,refund,payment,account,"Synthetic learner","Synthetic historical session","INR","Asia/Kolkata","NO_HOLD","ACTIVE",0,null,BigDecimal.ZERO,null,false,false,false,false,List.of());});
        when(service.submit(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new RefundAccountReopenModels.Result(review,refund,account,"SUBMITTED",false,false);});
        when(service.decide(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new RefundAccountReopenModels.Result(review,refund,account,"APPROVED",true,false);});
    }
    @AfterEach void close(){assertThat(TenantRequestContext.current()).isEmpty();SecurityContextHolder.clearContext();if(context!=null)context.close();}

    @Test void anonymousRevokedAndGuardianCannotReadOriginalAccountReviews() throws Exception {
        for(String token:List.of("",PARENT,"synthetic-reopen-revoked-session-00001"))for(String path:List.of(QUEUE,sourcePath())) {
            var request=get(path).param("schoolId",school.toString());if(!token.isEmpty())request.cookie(cookie(token));
            mvc.perform(request).andExpect(status().is(token.equals(PARENT)?403:401));
        }
        verifyNoInteractions(service);
    }
    @Test void readsRemainSchoolScopedPrivateAndNeverClaimRefundPosting() throws Exception {
        for(String path:List.of(QUEUE,sourcePath())) {
            var response=mvc.perform(get(path).cookie(cookie(READ)).param("schoolId",school.toString()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"))
                    .andExpect(header().string("X-Content-Type-Options","nosniff"))
                    .andExpect(jsonPath("$.schoolId").value(school.toString())).andExpect(jsonPath("$.refundPosted").value(false)).andReturn().getResponse();
            assertThat(response.getHeaders("Vary")).contains("Cookie");
            assertThat(response.getContentAsString()).doesNotContain("credentials","merchant_account_reference","payment_reference","evidence_sha256","requested_by_user_id");
        }
        verify(service).context(reader,school,refund);verify(service).queue(reader,school,"SUBMITTED",null,0,25);
    }
    @Test void readAndParentCapabilitiesCannotWriteEvenWithActualCsrf() throws Exception {
        Cookie csrf=csrf();
        for(String token:List.of(READ,PARENT))for(String path:List.of(sourcePath(),decisionPath()))
            mvc.perform(post(path).cookie(cookie(token),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(path.equals(sourcePath())?submitBody():decisionBody()))
                    .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void missingMismatchedOrOneSidedCsrfCannotCreateReviewsOrReopenAccounts() throws Exception {
        Cookie csrf=csrf();
        for(String path:List.of(sourcePath(),decisionPath())) {
            String body=path.equals(sourcePath())?submitBody():decisionBody();
            mvc.perform(post(path).cookie(cookie(WRITE)).contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(WRITE),csrf).contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(WRITE)).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body)).andExpect(status().isForbidden());
            mvc.perform(post(path).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN","synthetic-wrong-csrf").contentType("application/json").content(body)).andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
    @Test void actualSessionBindsExactOriginalVersionAndIndependentDecisionWithoutFinancialSuccess() throws Exception {
        Cookie csrf=csrf();
        mvc.perform(post(sourcePath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(submitBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUBMITTED")).andExpect(jsonPath("$.accountReopened").value(false)).andExpect(jsonPath("$.refundPosted").value(false));
        verify(service).submit(reviewer,refund,new RefundAccountReopenModels.Submit(school,clientId,3L,"Synthetic reviewed original account"));
        mvc.perform(post(decisionPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(decisionBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("APPROVED")).andExpect(jsonPath("$.accountReopened").value(true)).andExpect(jsonPath("$.refundPosted").value(false));
        verify(service).decide(reviewer,review,new RefundAccountReopenModels.DecisionCommand(school,"APPROVE","Synthetic independent review"));
    }
    @Test void invalidTermsAndUnboundedQueriesFailBeforeServiceWithoutEchoingPrivateInput() throws Exception {
        Cookie csrf=csrf();
        for(String body:List.of("{}",submitBody().replace("\"expectedAccountVersion\":3","\"expectedAccountVersion\":-1"),submitBody().replace("Synthetic reviewed original account","x".repeat(501))))
            mvc.perform(post(sourcePath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(decisionPath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(decisionBody().replace("APPROVE","SUCCESS"))).andExpect(status().isBadRequest());
        for(var invalid:List.of(new String[]{"page","10001"},new String[]{"pageSize","51"},new String[]{"status","SUCCESS"},new String[]{"q","synthetic-private-query".repeat(8)})) {
            String body=mvc.perform(get(QUEUE).cookie(cookie(READ)).param("schoolId",school.toString()).param(invalid[0],invalid[1])).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("synthetic-private-query",READ);
        }
        verifyNoInteractions(service);
    }
    @Test void untrustedOriginCannotReadOrWriteWithOtherwiseValidSessionAndCsrf() throws Exception {
        Cookie csrf=csrf();
        mvc.perform(get(QUEUE).cookie(cookie(READ)).param("schoolId",school.toString()).header("Origin","https://synthetic-untrusted.example.test")).andExpect(status().isForbidden());
        mvc.perform(post(sourcePath()).cookie(cookie(WRITE),csrf).header("X-CSRF-TOKEN",csrf.getValue()).header("Origin","https://synthetic-untrusted.example.test").contentType("application/json").content(submitBody())).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    private Cookie csrf() throws Exception {Cookie value=mvc.perform(get(QUEUE).cookie(cookie(READ)).param("schoolId",school.toString())).andExpect(status().isOk()).andReturn().getResponse().getCookie("educore_csrf");assertThat(value).isNotNull();clearInvocations(service);return value;}
    private Cookie cookie(String value){return new Cookie("educore_session",value);}
    private String sourcePath(){return ROOT+"/refunds/"+refund+"/account-reopening";}
    private String decisionPath(){return QUEUE+"/"+review+"/decision";}
    private String submitBody(){return "{\"schoolId\":\""+school+"\",\"clientRequestId\":\""+clientId+"\",\"expectedAccountVersion\":3,\"reason\":\"Synthetic reviewed original account\"}";}
    private String decisionBody(){return "{\"schoolId\":\""+school+"\",\"decision\":\"APPROVE\",\"reason\":\"Synthetic independent review\"}";}
    private AuthenticatedUser actor(String capability){return new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic-account-review",null,"Synthetic reviewer",true,org,Set.of(UUID.randomUUID()),Set.of(capability),List.of());}
    private Optional<SessionService.AuthenticatedSession> session(AuthenticatedUser user){return Optional.of(new SessionService.AuthenticatedSession(user,Instant.now().plusSeconds(60)));}
    private void assertTenant(AuthenticatedUser user){var value=TenantRequestContext.current().orElseThrow();assertThat(value.userId()).isEqualTo(user.userId());assertThat(value.organisationId()).isEqualTo(user.activeOrganisationId());}
    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config {
        @Bean RefundAccountReopenService reopening(){return mock(RefundAccountReopenService.class);}
        @Bean RefundAccountReopenController controller(RefundAccountReopenService service){return new RefundAccountReopenController(service);}
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean GlobalExceptionHandler advice(Clock clock){return new GlobalExceptionHandler(clock);}
        @Bean ApiSecurityErrorWriter errors(Clock clock){return new ApiSecurityErrorWriter(new ObjectMapper(),clock);}
        @Bean SessionService sessions(){return mock(SessionService.class);}
        @Bean PrivilegedMfaPolicy mfa(){return mock(PrivilegedMfaPolicy.class);}
        @Bean ApplicationProperties properties(){var properties=mock(ApplicationProperties.class);var security=mock(ApplicationProperties.Security.class);when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));when(properties.security()).thenReturn(security);when(security.sessionCookieName()).thenReturn("educore_session");return properties;}
        @Bean SessionAuthenticationFilter filter(SessionService sessions,ApplicationProperties properties,PrivilegedMfaPolicy mfa,ApiSecurityErrorWriter errors){return new SessionAuthenticationFilter(sessions,properties,mfa,errors);}
    }
}
