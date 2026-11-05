package com.educore.api.identity;

import static com.educore.api.identity.StaffAccountInvitationModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.common.api.ApiSecurityErrorWriter;
import com.educore.api.common.api.GlobalExceptionHandler;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.config.SecurityConfiguration;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.http.Cookie;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
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

/** Real HTTP/session/CSRF/MFA boundary; the separate PostgreSQL checkpoint proves actual grants and linkage. */
class StaffAccountInvitationSecurityTest {
    private final UUID organisation=UUID.randomUUID(),school=UUID.randomUUID(),staff=UUID.randomUUID(),invitation=UUID.randomUUID();
    private final String token=new SessionTokenCodec(new SecureRandom()).newRawToken();
    private final String password="Synthetic-only-"+UUID.randomUUID();
    private final ObjectMapper json=new ObjectMapper();
    private final Map<String,AuthenticatedUser> actors=new LinkedHashMap<>();
    private AnnotationConfigWebApplicationContext application;
    private StaffAccountInvitationService service;
    private MockMvc mvc;

    @BeforeEach void setup(){
        actors.put("admin",actor("identity.manage","people.manage"));actors.put("identity-only",actor("identity.manage"));
        actors.put("people-only",actor("people.manage"));actors.put("parent",actor("portal.guardian"));actors.put("staff",actor("people.read"));
        application=new AnnotationConfigWebApplicationContext();application.setServletContext(new MockServletContext());application.register(Config.class);application.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(application).addFilters(new StaffAccountInvitationPrivacyFilter()).apply(springSecurity()).build();
        service=application.getBean(StaffAccountInvitationService.class);SessionService sessions=application.getBean(SessionService.class);
        actors.forEach((name,user)->when(sessions.authenticate(sessionToken(name))).thenReturn(Optional.of(new SessionService.AuthenticatedSession(user,Instant.now().plusSeconds(3600)))));
        when(service.list(any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new ListResponse(school,staff,false,true,List.of(),List.of());});
        when(service.issue(any(),any(),any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return retained();});
        when(service.revoke(any(),any(),any(),any(),any())).thenReturn(retained());
        when(service.revealHandoff(any(),any(),any(),any(),any())).thenReturn(new HandoffResponse(invitation,token,Instant.now().plusSeconds(3600)));
        when(service.preview(any())).thenReturn(new PreviewResponse("Synthetic school","s***@example.test","teacher","Teacher",Instant.now().plusSeconds(3600),false,"ready"));
        when(service.acceptNew(any())).thenReturn(new AcceptanceResponse("accepted","sign_in"));
        when(service.acceptExisting(any(),any())).thenAnswer(call->{assertTenant(call.getArgument(0));return new AcceptanceResponse("accepted","sign_in");});
    }
    @AfterEach void cleanup(){assertThat(TenantRequestContext.current()).isEmpty();SecurityContextHolder.clearContext();if(application!=null)application.close();}

    @Test void allAdministrativeOperationsDenyMissingRevokedAndEitherMissingCapability()throws Exception{
        for(String name:List.of("","revoked","identity-only","people-only","parent","staff")){
            int expected=actors.containsKey(name)?403:401;
            privateResponse(mvc.perform(authenticated(get(base()),name)).andExpect(status().is(expected)));
            for(String endpoint:List.of(base(),base()+"/"+invitation+"/revocations",base()+"/"+invitation+"/handoffs")){
                var csrf=csrfCookie();
                privateResponse(mvc.perform(authenticated(post(endpoint),name).cookie(csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(endpoint.equals(base())?issueBody():changeBody()))
                        .andExpect(status().is(expected)));
            }
        }
        verifyNoInteractions(service);
    }
    @Test void administrativeMutationsRequireTheActualCsrfCookieAndMatchingHeader()throws Exception{
        for(String endpoint:List.of(base(),base()+"/"+invitation+"/revocations",base()+"/"+invitation+"/handoffs")){
            for(var call:List.of(post(endpoint).cookie(auth("admin")),post(endpoint).cookie(auth("admin"),csrfCookie()),
                    post(endpoint).cookie(auth("admin")).header("X-CSRF-TOKEN",csrfCookie().getValue()),
                    post(endpoint).cookie(auth("admin"),csrfCookie()).header("X-CSRF-TOKEN","synthetic-mismatched-csrf")))
                privateResponse(mvc.perform(call.contentType("application/json").content(endpoint.equals(base())?issueBody():changeBody())).andExpect(status().isForbidden()));
        }
        verifyNoInteractions(service);
    }
    @Test void issueUsesTheExactSchoolStaffTermsAndCurrentScopedActor()throws Exception{
        var csrf=csrfCookie();
        privateResponse(mvc.perform(post(base()).cookie(auth("admin"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(issueBody()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(invitation.toString())).andExpect(jsonPath("$.maskedDestination").value("s***@example.test"))
                .andExpect(jsonPath("$.setupToken").doesNotExist()).andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.tokenHash").doesNotExist()));
        verify(service).issue(eq(actors.get("admin")),eq(school),eq(staff),any(IssueRequest.class));
    }
    @Test void listAndExplicitOneUseHandoffHavePrivateHeadersAndDifferentSecretExposure()throws Exception{
        privateResponse(mvc.perform(get(base()).cookie(auth("admin"))).andExpect(status().isOk()).andExpect(jsonPath("$.manualHandoffEnabled").value(false)));
        var csrf=csrfCookie();
        privateResponse(mvc.perform(post(base()+"/"+invitation+"/handoffs").cookie(auth("admin"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(changeBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.setupToken").value(token)));
        verify(service).revealHandoff(eq(actors.get("admin")),eq(school),eq(staff),eq(invitation),any(HandoffRequest.class));
    }
    @Test void anonymousPreviewAndNewAcceptanceUsePurposeBoundTokensWithoutCreatingASession()throws Exception{
        privateResponse(mvc.perform(post(publicBase()+"/previews").contentType("application/json").content(json.writeValueAsString(Map.of("token",token))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.existingAccount").value(false)).andExpect(jsonPath("$.maskedDestination").value("s***@example.test")));
        var response=privateResponse(mvc.perform(post(publicBase()+"/acceptances").contentType("application/json").content(json.writeValueAsString(Map.of("token",token,"password",password,"confirmation",password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("accepted")).andExpect(jsonPath("$.nextAction").value("sign_in")));
        assertThat(response.andReturn().getResponse().getCookie("educore_session")).isNull();
        verify(service).acceptNew(new NewAcceptanceRequest(token,password,password));verify(service,never()).acceptExisting(any(),any());
    }
    @Test void existingAccountAcceptanceRequiresNormalSessionAndMatchingCsrfEvenWithAValidToken()throws Exception{
        var csrf=csrfCookie();String body=json.writeValueAsString(Map.of("token",token));
        for(String name:List.of("","revoked"))
            privateResponse(mvc.perform(authenticated(post(existingPath()),name).cookie(csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body)).andExpect(status().isUnauthorized()));
        privateResponse(mvc.perform(post(existingPath()).cookie(auth("staff")).contentType("application/json").content(body)).andExpect(status().isForbidden()));
        verifyNoInteractions(service);
        privateResponse(mvc.perform(post(existingPath()).cookie(auth("staff"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body)).andExpect(status().isOk()));
        verify(service).acceptExisting(actors.get("staff"),new ExistingAcceptanceRequest(token));
    }
    @Test void existingAcceptanceDoesNotEscapeThePrivilegedMfaFilterThroughAPublicAlias()throws Exception{
        var mfa=application.getBean(PrivilegedMfaPolicy.class);when(mfa.isRequired(actors.get("staff"))).thenReturn(true);
        var csrf=csrfCookie();
        privateResponse(mvc.perform(post(existingPath()).cookie(auth("staff"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(json.writeValueAsString(Map.of("token",token))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MFA_VERIFICATION_REQUIRED")));
        verify(service,never()).acceptExisting(any(),any());verify(mfa).recordDenied(actors.get("staff"));
    }
    @Test void malformedAndOutOfBoundsInputIsRejectedWithoutEchoingSecretsOrInvokingTheService()throws Exception{
        for(String endpoint:List.of(publicBase()+"/previews",publicBase()+"/acceptances"))
            for(String body:List.of("{}","{\"token\":\""+token,"{\"token\":\""+token+" extra\",\"password\":\""+password+"\",\"confirmation\":\""+password+"\"}")){
                var response=privateResponse(mvc.perform(post(endpoint).contentType("application/json").content(body)).andExpect(status().isBadRequest()));
                assertThat(response.andReturn().getResponse().getContentAsString()).doesNotContain(token,password,"HttpMessageNotReadableException");
            }
        var csrf=csrfCookie();
        for(String body:List.of(issueBody().replace("\"ttlHours\":24","\"ttlHours\":169"),issueBody().replace("\"expectedStaffVersion\":0","\"expectedStaffVersion\":-1"),"{}"))
            privateResponse(mvc.perform(post(base()).cookie(auth("admin"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(body)).andExpect(status().isBadRequest()));
        verifyNoInteractions(service);
    }
    @Test void untrustedCorsOriginCannotReachPublicAcceptanceOrPrivateHandoff()throws Exception{
        var csrf=csrfCookie();
        privateResponse(mvc.perform(post(publicBase()+"/acceptances").header("Origin","https://untrusted.invalid").contentType("application/json")
                .content(json.writeValueAsString(Map.of("token",token,"password",password,"confirmation",password)))).andExpect(status().isForbidden()));
        privateResponse(mvc.perform(post(base()+"/"+invitation+"/handoffs").cookie(auth("admin"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).header("Origin","https://untrusted.invalid")
                .contentType("application/json").content(changeBody())).andExpect(status().isForbidden()));
        verifyNoInteractions(service);
    }
    @Test void genericExpiredUsedTokenAndReviewedConflictsStayPrivateWithoutSecretEcho()throws Exception{
        doThrow(new ApiException(HttpStatus.GONE,"STAFF_INVITATION_INVALID","This staff invitation is unavailable.")).when(service).preview(any());
        var response=privateResponse(mvc.perform(post(publicBase()+"/previews").contentType("application/json").content(json.writeValueAsString(Map.of("token",token))))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("STAFF_INVITATION_INVALID")));
        assertThat(response.andReturn().getResponse().getContentAsString()).doesNotContain(token,password);
        doThrow(new ApiException(HttpStatus.CONFLICT,"STAFF_INVITATION_HANDOFF_ALREADY_REVEALED","Revoke and reissue the invitation.")).when(service).revealHandoff(any(),any(),any(),any(),any());
        var csrf=csrfCookie();
        privateResponse(mvc.perform(post(base()+"/"+invitation+"/handoffs").cookie(auth("admin"),csrf).header("X-CSRF-TOKEN",csrf.getValue()).contentType("application/json").content(changeBody()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.setupToken").doesNotExist()));
    }

    private String base(){return "/api/v1/schools/"+school+"/staff/"+staff+"/account-invitations";}
    private static String publicBase(){return "/api/v1/public/staff-account-invitations";}
    private static String existingPath(){return "/api/v1/staff-account-invitations/acceptances";}
    private String issueBody(){return json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"expectedStaffVersion",0,"roleCode","teacher","ttlHours",24,"reason","Synthetic identity setup review"));}
    private String changeBody(){return json.writeValueAsString(Map.of("requestId",UUID.randomUUID(),"expectedVersion",0,"reason","Synthetic identity setup review"));}
    private static Cookie csrfCookie(){return new Cookie("educore_csrf","synthetic-staff-invitation-csrf-00001");}
    private static String sessionToken(String name){return "synthetic-staff-invitation-"+name+"-session-00001";}
    private static Cookie auth(String name){return new Cookie("educore_session",sessionToken(name));}
    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder call,String name){return name.isEmpty()?call:call.cookie(auth(name));}
    private AuthenticatedUser actor(String...capabilities){return new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic-staff@example.test","synthetic-staff@example.test","Synthetic invitation actor",true,organisation,Set.of(UUID.randomUUID()),Set.of(capabilities),List.of());}
    private static void assertTenant(AuthenticatedUser user){var context=TenantRequestContext.current().orElseThrow();assertThat(context.userId()).isEqualTo(user.userId());assertThat(context.organisationId()).isEqualTo(user.activeOrganisationId());}
    private static ResultActions privateResponse(ResultActions response)throws Exception{
        response.andExpect(header().string("Cache-Control","private, no-store")).andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Referrer-Policy","no-referrer"));
        assertThat(response.andReturn().getResponse().getHeaders("Vary")).contains("Cookie");return response;
    }
    private Invitation retained(){return new Invitation(invitation,school,staff,"teacher","Teacher","s***@example.test","ISSUED","AWAITING_HANDOFF",Instant.now().plusSeconds(3600),Instant.now(),null,null,null,0,true,false);}

    @EnableWebMvc @EnableWebSecurity @Import(SecurityConfiguration.class)
    static class Config{
        @Bean StaffAccountInvitationService service(){return mock(StaffAccountInvitationService.class);}
        @Bean StaffAccountInvitationController controller(StaffAccountInvitationService service){return new StaffAccountInvitationController(service);}
        @Bean Clock clock(){return Clock.systemUTC();}
        @Bean GlobalExceptionHandler advice(Clock clock){return new GlobalExceptionHandler(clock);}
        @Bean ApiSecurityErrorWriter errors(Clock clock){return new ApiSecurityErrorWriter(new ObjectMapper(),clock);}
        @Bean SessionService sessions(){return mock(SessionService.class);}
        @Bean PrivilegedMfaPolicy mfa(){return mock(PrivilegedMfaPolicy.class);}
        @Bean ApplicationProperties properties(){var properties=mock(ApplicationProperties.class);var security=mock(ApplicationProperties.Security.class);when(properties.allowedOrigins()).thenReturn(List.of("http://localhost:3000"));when(properties.security()).thenReturn(security);when(security.sessionCookieName()).thenReturn("educore_session");return properties;}
        @Bean SessionAuthenticationFilter filter(SessionService sessions,ApplicationProperties properties,PrivilegedMfaPolicy mfa,ApiSecurityErrorWriter errors){return new SessionAuthenticationFilter(sessions,properties,mfa,errors);}
    }
}
