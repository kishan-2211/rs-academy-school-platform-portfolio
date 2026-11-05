package com.educore.api.identity;

import static com.educore.api.identity.StaffAccountInvitationModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.common.api.ApiException;
import jakarta.validation.Validation;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StaffAccountInvitationModelsTest {
    @Test void issueTermsRequireAnExactRequestIdentityBoundedExpiryAndProfileVersion(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var validator=factory.getValidator();
            assertThat(validator.validate(new IssueRequest(UUID.randomUUID(),0,"teacher",1,"Synthetic setup review"))).isEmpty();
            assertThat(validator.validate(new IssueRequest(UUID.randomUUID(),1,"finance_officer",168,"Synthetic setup review"))).isEmpty();
            for(var request:java.util.List.of(new IssueRequest(null,0,"teacher",24,"Synthetic review"),
                    new IssueRequest(UUID.randomUUID(),-1,"teacher",24,"Synthetic review"),new IssueRequest(UUID.randomUUID(),0,"teacher",0,"Synthetic review"),
                    new IssueRequest(UUID.randomUUID(),0,"teacher",169,"Synthetic review"),new IssueRequest(UUID.randomUUID(),0,"teacher",24,"no")))
                assertThat(validator.validate(request)).isNotEmpty();
        }
    }
    @Test void tokensAreExactlyTheGeneratedUrlSafeShapeWithNoWhitespaceOrQuerySyntax(){
        try(var factory=Validation.buildDefaultValidatorFactory()){
            var validator=factory.getValidator();String token=new SessionTokenCodec(new SecureRandom()).newRawToken();
            assertThat(validator.validate(new PreviewRequest(token))).isEmpty();
            for(String invalid:java.util.List.of("",token+" ",token.substring(1),"?token="+token,"+".repeat(43)))
                assertThat(validator.validate(new PreviewRequest(invalid))).isNotEmpty();
        }
    }
    @Test void secretBearingRecordsNeverExposeTheirContentsThroughToString(){
        String token=new SessionTokenCodec(new SecureRandom()).newRawToken(),password="Synthetic-only-"+UUID.randomUUID();
        for(Object value:java.util.List.of(new PreviewRequest(token),new ExistingAcceptanceRequest(token),new NewAcceptanceRequest(token,password,password),
                new HandoffResponse(UUID.randomUUID(),token,Instant.now().plusSeconds(3600))))
            assertThat(value.toString()).contains("redacted").doesNotContain(token,password);
    }
    @Test void privilegedPasswordGuardAcceptsLongPassphrasesWithoutInventingCharacterCompositionRules(){
        String passphrase="Synthetic-only-"+UUID.randomUUID();
        StaffAccountInvitationService.validatePassword(passphrase,passphrase);
        String unicode="कृत्रिम केवल परीक्षण शब्दसमूह";StaffAccountInvitationService.validatePassword(unicode,unicode);
    }
    @Test void passwordGuardRejectsShortMismatchedControlOrWhitespaceOnlyValues(){
        for(String password:java.util.List.of("short"," ".repeat(20),"x".repeat(201),"x".repeat(16)+"\n","x".repeat(16)+"\u0000"))
            assertThatThrownBy(()->StaffAccountInvitationService.validatePassword(password,password)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo("STAFF_INVITATION_PASSWORD_INVALID"));
        assertThatThrownBy(()->StaffAccountInvitationService.validatePassword(null,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->StaffAccountInvitationService.validatePassword("Synthetic-only-"+UUID.randomUUID(),"another value"))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo("STAFF_INVITATION_PASSWORD_INVALID"));
    }
    @Test void staffRoleAllowlistDoesNotGrantStudentParentOrNewSystemRoles(){
        assertThat(StaffAccountInvitationService.ROLE_CODES).containsExactlyInAnyOrder("school_admin","admissions_manager","teacher","finance_officer","principal_director","auditor","staff_operations")
                .doesNotContain("parent_guardian","student","transport_driver","super_admin");
    }
}
