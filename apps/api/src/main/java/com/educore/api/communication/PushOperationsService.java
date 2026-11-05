package com.educore.api.communication;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushOperationsService {
    private static final String SECRET_REFERENCE="env:APP_PUSH_DELIVERY_PRIVATE_KEY";
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final WebPushDeliveryConfiguration configuration;
    private final boolean workerEnabled;
    private final Clock clock;

    public PushOperationsService(NamedParameterJdbcTemplate jdbc,SecurityAuditService audit,
            WebPushDeliveryConfiguration configuration,@Value("${app.push.delivery.worker-enabled:false}") boolean workerEnabled,Clock clock) {
        this.jdbc=jdbc; this.audit=audit; this.configuration=configuration; this.workerEnabled=workerEnabled; this.clock=clock;
    }

    @Transactional
    public PushOperationsResponse snapshot(AuthenticatedUser user,UUID schoolId) {
        var scope=scope(user,schoolId);
        var result=load(scope);
        audit.recordScoped(user,scope.membershipId(),schoolId,"communication.push_operations.viewed","SCHOOL",schoolId.toString(),
                SecurityAuditService.AuditOutcome.SUCCEEDED,"Viewed safe push operating counts",Map.of());
        return result;
    }

    @Transactional
    public PushOperationsResponse changeProvider(AuthenticatedUser user,PushOperationsController.ProviderChange request) {
        var initial=scope(user,request.schoolId());
        if(!initial.canApprove() || !user.mfaVerified()) throw denied();
        if(!List.of("ACTIVE","SUSPENDED").contains(request.status()==null?"":request.status())
                || request.reason()==null || request.reason().strip().length()<3 || request.reason().length()>500
                || request.reason().chars().anyMatch(Character::isISOControl)
                || request.expectedVersion()!=null && request.expectedVersion()<0)
            throw new ApiException(HttpStatus.BAD_REQUEST,"PUSH_PROVIDER_CHANGE_INVALID","Provide a supported state, current version and a short decision reason.");
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key","push-provider:"+request.schoolId()),Integer.class);
        var scope=scope(user,request.schoolId()); // Recheck after waiting for another decision.
        if(!scope.canApprove()) throw denied();
        var current=provider(scope);
        if(!Objects.equals(current==null?null:current.version(),request.expectedVersion()))
            throw conflict("PUSH_PROVIDER_STALE","The provider changed. Refresh before making another decision.");
        if("SUSPENDED".equals(request.status()) && (current==null || "INACTIVE".equals(current.status())))
            throw conflict("PUSH_PROVIDER_NOT_CONFIGURED","No live push provider has been configured for this school.");
        if("ACTIVE".equals(request.status()) && current!=null && !current.compatible())
            throw conflict("PUSH_PROVIDER_MAPPING_CONFLICT","The existing provider mapping needs deployment review; it will not be overwritten.");
        if(current!=null && current.status().equals(request.status())) return load(scope);
        UUID id=current==null?UUID.randomUUID():current.id();
        long nextVersion=current==null?0:current.version()+1;
        var p=scope.params().addValue("id",id).addValue("status",request.status()).addValue("now",clock.instant())
                .addValue("reference",SECRET_REFERENCE);
        if(current==null) {
            jdbc.update("""
                    INSERT INTO communication_provider_configs(id,organisation_id,school_id,channel,environment,provider_code,
                      credential_secret_reference,status,approved_by_membership_id,approved_at,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,'PUSH','LIVE','WEB_PUSH',:reference,'ACTIVE',:membershipId,:now,:now,:now,0)
                    """,p);
        } else {
            jdbc.update("""
                    UPDATE communication_provider_configs SET status=:status,
                      credential_secret_reference=CASE WHEN :status='ACTIVE' THEN :reference ELSE credential_secret_reference END,
                      approved_by_membership_id=CASE WHEN :status='ACTIVE' THEN :membershipId ELSE approved_by_membership_id END,
                      approved_at=CASE WHEN :status='ACTIVE' THEN :now ELSE approved_at END,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                    """,p);
        }
        audit.recordScoped(user,scope.membershipId(),scope.schoolId(),
                "communication.push_provider."+("ACTIVE".equals(request.status())?"approved":"suspended"),
                "COMMUNICATION_PROVIDER_CONFIG",id.toString(),SecurityAuditService.AuditOutcome.SUCCEEDED,request.reason().strip(),
                Map.of("previousStatus",current==null?"NOT_CONFIGURED":current.status(),"status",request.status(),"version",nextVersion));
        return load(scope);
    }

    private PushOperationsResponse load(Scope scope) {
        var provider=provider(scope);
        Instant since=clock.instant().minus(30,ChronoUnit.DAYS);
        var p=scope.params().addValue("since",since);
        var parents=jdbc.query("""
                SELECT status,count(*) total FROM communication_deliveries
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND channel='PUSH' AND created_at>=:since
                GROUP BY status ORDER BY status
                """,p,(r,n)->new PushOperationsResponse.StateCount(r.getString(1),r.getLong(2)));
        var devices=jdbc.query("""
                SELECT status,count(*) total FROM communication_push_jobs
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND created_at>=:since
                GROUP BY status ORDER BY status
                """,p,(r,n)->new PushOperationsResponse.StateCount(r.getString(1),r.getLong(2)));
        var failures=jdbc.query("""
                SELECT last_error_code,count(*) total FROM communication_push_jobs
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND created_at>=:since
                  AND status IN ('FAILED','RETRY') AND last_error_code IS NOT NULL
                GROUP BY last_error_code ORDER BY total DESC,last_error_code LIMIT 10
                """,p,(r,n)->new PushOperationsResponse.StateCount(r.getString(1),r.getLong(2)));
        return new PushOperationsResponse(scope.schoolId(),scope.canApprove(),configuration.enabled(),workerEnabled,
                provider==null?"NOT_CONFIGURED":provider.status(),provider==null?null:provider.version(),
                provider==null||provider.compatible(),since,parents,devices,failures);
    }

    private Provider provider(Scope scope) {
        return jdbc.query("""
                SELECT id,status,row_version,provider_code,credential_secret_reference FROM communication_provider_configs
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND channel='PUSH' AND environment='LIVE'
                """,scope.params(),r->r.next()?new Provider(r.getObject(1,UUID.class),r.getString(2),r.getLong(3),
                        "WEB_PUSH".equals(r.getString(4))&&(r.getString(5)==null||SECRET_REFERENCE.equals(r.getString(5)))):null);
    }

    private Scope scope(AuthenticatedUser user,UUID schoolId) {
        if(user==null || schoolId==null || user.activeOrganisationId()==null || user.membershipIds().isEmpty()) throw denied();
        var p=new MapSqlParameterSource("organisationId",user.activeOrganisationId()).addValue("schoolId",schoolId)
                .addValue("userId",user.userId()).addValue("membershipIds",user.membershipIds()).addValue("now",clock.instant());
        var rows=jdbc.query("""
                SELECT membership.id,bool_or(capability.capability_code='communication.approve') can_approve
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id AND school.organisation_id=membership.organisation_id
                JOIN organisations organisation ON organisation.id=membership.organisation_id
                JOIN user_accounts account ON account.id=membership.user_id
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN roles role ON role.id=mr.role_id AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id)
                JOIN role_capabilities capability ON capability.role_id=role.id
                WHERE membership.user_id=:userId AND membership.id IN (:membershipIds)
                  AND membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                  AND membership.status='ACTIVE' AND membership.valid_from<=:now
                  AND (membership.valid_until IS NULL OR membership.valid_until>:now)
                  AND school.status='ACTIVE' AND organisation.status='ACTIVE' AND account.status='ACTIVE'
                  AND capability.capability_code IN ('communication.configure','communication.approve')
                GROUP BY membership.id ORDER BY can_approve DESC,membership.id LIMIT 1
                """,p,(r,n)->new Scope(user.activeOrganisationId(),schoolId,r.getObject(1,UUID.class),r.getBoolean(2)));
        if(rows.isEmpty()) throw denied();
        return rows.getFirst();
    }
    private static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN,"PUSH_OPERATIONS_DENIED","Push operations are unavailable for this school or require an MFA-verified approver."); }
    private static ApiException conflict(String code,String message) { return new ApiException(HttpStatus.CONFLICT,code,message); }
    private record Provider(UUID id,String status,long version,boolean compatible) {}
    private record Scope(UUID organisationId,UUID schoolId,UUID membershipId,boolean canApprove) {
        MapSqlParameterSource params() { return new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId).addValue("membershipId",membershipId); }
    }
}
