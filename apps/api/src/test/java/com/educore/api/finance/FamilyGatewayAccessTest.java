package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.tenancy.TenantRequestContext;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked live-scope contracts; real non-owner RLS/relationships are tested separately. */
class FamilyGatewayAccessTest {
    private final UUID org=UUID.randomUUID(), school=UUID.randomUUID(), learner=UUID.randomUUID(), member=UUID.randomUUID(), account=UUID.randomUUID();
    private final AuthenticatedUser parent=new AuthenticatedUser(UUID.randomUUID(),UUID.randomUUID(),"synthetic-family",null,
            "Synthetic Family",false,org,Set.of(member),Set.of("portal.guardian"),List.of());
    private final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    private final FamilyGatewayAccess service=new FamilyGatewayAccess(jdbc);
    private boolean linked=true, matchingAccount=true;

    @AfterEach void cleanup() { TransactionSynchronizationManager.clear(); assertThat(TenantRequestContext.current()).isEmpty(); }
    @Test void noCallerCannotReadAnySchoolScope() { denied(()->service.requireChild(null,learner)); verifyNoInteractions(jdbc); }
    @Test void missingTransactionCannotReadScope() {
        try(var ignored=TenantRequestContext.open(parent.userId(),org)) { denied(()->service.requireChild(parent,learner)); }
        verifyNoInteractions(jdbc);
    }
    @Test void missingContextCannotUseCachedGuardianCapabilities() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        denied(()->service.requireChild(parent,learner)); verifyNoInteractions(jdbc);
    }
    @Test void backgroundScopeCannotImpersonateAGuardian() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try(var ignored=TenantRequestContext.open(null,org)) { denied(()->service.requireChild(parent,learner)); }
        verifyNoInteractions(jdbc);
    }
    @Test void foreignActorCannotReuseAnotherParentsSnapshot() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try(var ignored=TenantRequestContext.open(UUID.randomUUID(),org)) { denied(()->service.requireChild(parent,learner)); }
        verifyNoInteractions(jdbc);
    }
    @Test void foreignOrganisationCannotReuseFamilySnapshot() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try(var ignored=TenantRequestContext.open(parent.userId(),UUID.randomUUID())) { denied(()->service.requireChild(parent,learner)); }
        verifyNoInteractions(jdbc);
    }
    @Test void emptySessionMembershipsCannotUseOtherUnselectedSchoolGrants() {
        var noMembership=new AuthenticatedUser(parent.userId(),parent.sessionId(),parent.loginIdentifier(),null,parent.displayName(),false,org,Set.of(),Set.of("portal.guardian"),List.of());
        run(()->denied(()->service.requireChild(noMembership,learner))); verifyNoInteractions(jdbc);
    }
    @Test void missingLearnerDoesNotEnumerateOtherChildren() { run(()->denied(()->service.requireChild(parent,null))); verifyNoInteractions(jdbc); }
    @Test void liveScopeRequiresDatabaseActorSchoolRelationshipAndActualWallClock() throws Exception {
        stub(); run(()->assertThat(service.requireChild(parent,learner)).isEqualTo(new FamilyGatewayAccess.Scope(org,school,learner,member)));
    }
    @Test void revokedOrMissingLiveRelationshipIsNotFoundRatherThanRoleFallback() throws Exception {
        linked=false; stub(); run(()->denied(()->service.requireChild(parent,learner)));
    }
    @Test void historicalClosedAccountCanBeReadWithoutCurrentEnrolmentGuessing() throws Exception {
        stub(); run(()-> {
            var value=service.requireAccount(parent,learner,account);
            assertThat(value.accountId()).isEqualTo(account); assertThat(value.status()).isEqualTo("CLOSED");
            assertThat(value.sessionName()).isEqualTo("Synthetic Earlier Session");
        });
    }
    @Test void differentChildAccountCannotBeReadThroughAValidRelationship() throws Exception {
        matchingAccount=false; stub(); run(()->denied(()->service.requireAccount(parent,learner,account)));
    }
    @Test void missingAccountDoesNotBypassChildAuthorization() throws Exception {
        stub(); run(()->denied(()->service.requireAccount(parent,learner,null)));
    }
    @Test void internalScopeDiagnosticsDoNotPrintLearnerOrAccountIdentity() {
        var scope=new FamilyGatewayAccess.Scope(org,school,learner,member);
        assertThat(scope.toString()).doesNotContain(learner.toString(),member.toString());
        assertThat(new FamilyGatewayAccess.AccountScope(scope,account,"ACTIVE","INR","Synthetic Earlier Session").toString())
                .doesNotContain(account.toString(),"Synthetic Earlier Session");
    }

    private void run(Runnable action) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try(var ignored=TenantRequestContext.open(parent.userId(),org)) { action.run(); }
    }
    private static void denied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error-> {
            assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND); assertThat(error.code()).isEqualTo("FAMILY_PAYMENT_NOT_FOUND");
        });
    }
    @SuppressWarnings("unchecked") private void stub() throws Exception {
        doAnswer(call-> {
            String sql=call.getArgument(0); SqlParameterSource p=call.getArgument(1); RowMapper<Object> mapper=call.getArgument(2);
            assertThat(p.getValue("organisationId")).isEqualTo(org); assertThat(p.getValue("learnerId")).isEqualTo(learner);
            var row=mock(ResultSet.class);
            if(sql.contains("SELECT membership.school_id")) {
                assertThat(sql).contains("membership.id IN(:membershipIds)","membership.valid_from<=clock_timestamp()",
                        "membership.valid_until>clock_timestamp()","current_app_user_id()=:userId","current_app_organisation_id()=:organisationId",
                        "relationship.portal_access_status='ACTIVE'","capability.capability_code='portal.guardian'",
                        "role.organisation_id=membership.organisation_id");
                assertThat(p.getValue("membershipIds")).isEqualTo(parent.membershipIds());
                if(!linked) return List.of();
                when(row.getObject("school_id",UUID.class)).thenReturn(school); when(row.getObject("membership_id",UUID.class)).thenReturn(member);
            } else {
                assertThat(sql).contains("account.learner_id=:learnerId","account.school_id=:schoolId","session.organisation_id=account.organisation_id")
                        .doesNotContain("student_enrolments","account.status='ACTIVE'");
                if(!matchingAccount) return List.of();
                when(row.getObject("id",UUID.class)).thenReturn(account); when(row.getString("status")).thenReturn("CLOSED");
                when(row.getString("currency_code")).thenReturn("INR"); when(row.getString("session_name")).thenReturn("Synthetic Earlier Session");
            }
            return List.of(mapper.mapRow(row,0));
        }).when(jdbc).query(anyString(),any(SqlParameterSource.class),any(RowMapper.class));
    }
}
