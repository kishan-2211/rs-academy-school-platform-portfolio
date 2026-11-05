package com.educore.api.identity;

import static com.educore.api.identity.StaffAccountInvitationModels.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.people.PeopleStaffAccountBridge;
import com.educore.api.people.PeopleStaffAccountBridge.StaffAccountCandidate;
import jakarta.validation.Validator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity owns grants; People owns employment. All acceptance changes commit or roll back together. */
@Service
public class StaffAccountInvitationService {
    static final Set<String> ROLE_CODES = Set.of("school_admin", "admissions_manager", "teacher", "finance_officer",
            "principal_director", "auditor", "staff_operations");
    private static final String INVALID_MESSAGE = "This staff invitation is invalid, expired, or no longer available.";
    private static final String COLUMNS = """
            id,organisation_id,school_id,staff_profile_id,request_id,request_fingerprint,
            staff_version,staff_name,school_name,target_email_normalized,staff_language,staff_joined_on,
            role_id,role_code,role_name,role_capabilities,role_capabilities_hash,token_hash,token_ciphertext,token_nonce,
            status,delivery_status,expires_at,created_by_user_id,created_by_membership_id,created_at,
            accepted_at,revoked_at,handoff_at,row_version
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final PeopleStaffAccountBridge people;
    private final UserAccountRepository users;
    private final UserSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokenCodec tokens;
    private final StaffAccountInvitationTokenProtector protector;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final Validator validator;
    private final boolean manualHandoffEnabled;

    public StaffAccountInvitationService(NamedParameterJdbcTemplate jdbc, PeopleStaffAccountBridge people,
            UserAccountRepository users, UserSessionRepository sessions, PasswordEncoder passwordEncoder,
            SessionTokenCodec tokens, StaffAccountInvitationTokenProtector protector, SecurityAuditService audit,
            Clock clock, Validator validator,
            @Value("${app.staff-invitations.manual-handoff-enabled:false}") boolean manualHandoffEnabled) {
        this.jdbc=jdbc;this.people=people;this.users=users;this.sessions=sessions;this.passwordEncoder=passwordEncoder;
        this.tokens=tokens;this.protector=protector;this.audit=audit;this.clock=clock;this.validator=validator;
        this.manualHandoffEnabled=manualHandoffEnabled;
    }

    @Transactional(readOnly=true)
    public ListResponse list(AuthenticatedUser user, UUID schoolId, UUID staffId) {
        Actor actor=authorize(user,schoolId,false);
        if(staffId==null)throw terms();
        List<Invitation> items=jdbc.query("SELECT "+COLUMNS+" FROM staff_account_invitations WHERE organisation_id=:organisationId AND school_id=:schoolId AND staff_profile_id=:staffId ORDER BY created_at DESC,id DESC LIMIT 30",
                scope(actor.organisationId(),schoolId,staffId),this::stored).stream().map(this::response).toList();
        // History remains available after account linkage; the unlinked-only People bridge is not a list reader.
        List<RoleOption> roles=ROLE_CODES.stream().sorted().map(this::role).map(value->new RoleOption(value.code(),value.name(),value.capabilities())).toList();
        return new ListResponse(schoolId,staffId,manualHandoffEnabled,true,roles,items);
    }

    @Transactional
    public Invitation issue(AuthenticatedUser user, UUID schoolId, UUID staffId, IssueRequest request) {
        validate(request);String reason=reason(request.reason());
        if(!ROLE_CODES.contains(request.roleCode()))throw roleInvalid();
        Actor actor=authorize(user,schoolId,true);lockStaff(actor.organisationId(),schoolId,staffId);
        String fingerprint=fingerprint("issue",actor.userId(),schoolId,staffId,request.expectedStaffVersion(),request.roleCode(),request.ttlHours(),reason);
        MapSqlParameterSource parameters=scope(actor.organisationId(),schoolId,staffId).addValue("requestId",request.requestId());
        List<Stored> replay=jdbc.query("SELECT "+COLUMNS+" FROM staff_account_invitations WHERE organisation_id=:organisationId AND request_id=:requestId FOR UPDATE",parameters,this::stored);
        if(!replay.isEmpty()){
            Stored previous=replay.getFirst();
            if(!previous.schoolId().equals(schoolId)||!previous.staffId().equals(staffId)||!previous.requestFingerprint().equals(fingerprint))throw requestConflict();
            return response(previous);
        }
        if(requestEvent(actor.organisationId(),request.requestId())!=null)throw requestConflict();
        StaffAccountCandidate candidate=people.lockCandidate(actor.organisationId(),schoolId,staffId);
        if(candidate.version()!=request.expectedStaffVersion())throw changed();
        Role selected=role(request.roleCode());
        Instant now=clock.instant();
        List<Stored> previous=jdbc.query("SELECT "+COLUMNS+" FROM staff_account_invitations WHERE organisation_id=:organisationId AND school_id=:schoolId AND staff_profile_id=:staffId AND status='ISSUED' FOR UPDATE",parameters,this::stored);
        for(Stored old:previous)terminate(user,actor,old,old.expiresAt().isAfter(now)?"REVOKED":"EXPIRED","Superseded by a new staff invitation",null,null,now);
        UUID invitationId=UUID.randomUUID();String rawToken=tokens.newRawToken();
        var protectedToken=protector.protect(rawToken,actor.organisationId(),schoolId,invitationId);
        jdbc.update("""
                INSERT INTO staff_account_invitations(id,organisation_id,school_id,staff_profile_id,request_id,request_fingerprint,
                  staff_version,staff_name,school_name,target_email_normalized,staff_language,staff_joined_on,
                  role_id,role_code,role_name,role_capabilities,role_capabilities_hash,token_hash,token_ciphertext,token_nonce,
                  status,delivery_status,expires_at,created_by_user_id,created_by_membership_id,created_at,creation_reason,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:staffId,:requestId,:fingerprint,:staffVersion,:staffName,:schoolName,:email,:language,:joinedOn,
                  :roleId,:roleCode,:roleName,ARRAY(SELECT capability_code FROM role_capabilities WHERE role_id=:roleId ORDER BY capability_code),:roleHash,
                  :tokenHash,:ciphertext,:nonce,'ISSUED','AWAITING_HANDOFF',:expiresAt,:actorUser,:actorMembership,:now,:reason,:now,0)
                """,parameters.addValue("id",invitationId).addValue("fingerprint",fingerprint).addValue("staffVersion",candidate.version())
                .addValue("staffName",candidate.displayName()).addValue("schoolName",candidate.schoolName()).addValue("email",candidate.email())
                .addValue("language",candidate.language()).addValue("joinedOn",candidate.joinedOn()).addValue("roleId",selected.id())
                .addValue("roleCode",selected.code()).addValue("roleName",selected.name()).addValue("roleHash",selected.hash())
                .addValue("tokenHash",tokens.hash(rawToken)).addValue("ciphertext",protectedToken.ciphertext()).addValue("nonce",protectedToken.nonce())
                .addValue("expiresAt",Timestamp.from(now.plus(request.ttlHours(),ChronoUnit.HOURS))).addValue("actorUser",actor.userId())
                .addValue("actorMembership",actor.membershipId()).addValue("now",Timestamp.from(now)).addValue("reason",reason));
        Stored created=byId(actor.organisationId(),schoolId,staffId,invitationId,false);
        event(user,actor,created,"ISSUED",request.requestId(),fingerprint,reason,now);
        return response(created);
    }

    @Transactional
    public Invitation revoke(AuthenticatedUser user,UUID schoolId,UUID staffId,UUID invitationId,RevokeRequest request){
        validate(request);String reason=reason(request.reason());Actor actor=authorize(user,schoolId,true);lockStaff(actor.organisationId(),schoolId,staffId);
        String fingerprint=fingerprint("revoke",actor.userId(),schoolId,staffId,invitationId,request.expectedVersion(),reason);
        RequestEvent replay=requestEvent(actor.organisationId(),request.requestId());
        if(replay!=null){requireReplay(replay,invitationId,"REVOKED",fingerprint);return response(byId(actor.organisationId(),schoolId,staffId,invitationId,false));}
        Stored invitation=byId(actor.organisationId(),schoolId,staffId,invitationId,true);requireOpen(invitation);
        if(invitation.version()!=request.expectedVersion())throw changed();
        return response(terminate(user,actor,invitation,"REVOKED",reason,request.requestId(),fingerprint,clock.instant()));
    }

    @Transactional
    public HandoffResponse revealHandoff(AuthenticatedUser user,UUID schoolId,UUID staffId,UUID invitationId,HandoffRequest request){
        validate(request);String reason=reason(request.reason());Actor actor=authorize(user,schoolId,true);
        if(!manualHandoffEnabled)throw conflict("HANDOFF_DISABLED","Private invitation handoff is not enabled.");
        lockStaff(actor.organisationId(),schoolId,staffId);
        RequestEvent replay=requestEvent(actor.organisationId(),request.requestId());
        if(replay!=null){
            String expected=fingerprint("handoff",actor.userId(),schoolId,staffId,invitationId,request.expectedVersion(),reason);
            requireReplay(replay,invitationId,"HANDOFF_REVEALED",expected);
            throw handoffAlreadyRevealed();
        }
        Stored invitation=byId(actor.organisationId(),schoolId,staffId,invitationId,true);requireOpen(invitation);
        if(invitation.handoffAt()!=null)throw handoffAlreadyRevealed();
        if(invitation.version()!=request.expectedVersion())throw changed();
        requireCurrentCandidate(invitation);requireIssuer(invitation);requireFrozenRole(invitation);
        String raw=protector.reveal(new StaffAccountInvitationTokenProtector.ProtectedToken(invitation.ciphertext(),invitation.nonce()),invitation.organisationId(),schoolId,invitation.id());
        if(!tokens.hash(raw).equals(invitation.tokenHash()))throw changed();
        Instant now=clock.instant();
        jdbc.update("""
                UPDATE staff_account_invitations SET delivery_status='HANDOFF_REVEALED',token_ciphertext=NULL,token_nonce=NULL,
                  handoff_at=:now,handoff_by_membership_id=:actor,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND status='ISSUED' AND row_version=:version
                """,parameters(invitation).addValue("now",Timestamp.from(now)).addValue("actor",actor.membershipId()));
        Stored updated=byId(actor.organisationId(),schoolId,staffId,invitationId,false);
        event(user,actor,updated,"HANDOFF_REVEALED",request.requestId(),fingerprint("handoff",actor.userId(),schoolId,staffId,invitationId,request.expectedVersion(),reason),reason,now);
        // The ciphertext is gone in this same transaction. A lost HTTP result cannot be replayed to reveal it again.
        return new HandoffResponse(invitation.id(),raw,invitation.expiresAt());
    }

    @Transactional
    public PreviewResponse preview(PreviewRequest request){
        validateTokenRequest(request);Stored invitation=discover(request.token());
        lockStaff(invitation.organisationId(),invitation.schoolId(),invitation.staffId());
        Stored current=byToken(tokens.hash(request.token()),true);requirePublicOpen(current);requireCurrentCandidate(current);
        requireIssuer(current);requireFrozenRole(current);
        Boolean existing=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_accounts WHERE email_normalized=:email)",new MapSqlParameterSource("email",current.email()),Boolean.class);
        return new PreviewResponse(current.schoolName(),maskEmail(current.email()),current.roleCode(),current.roleName(),current.expiresAt(),Boolean.TRUE.equals(existing),"ready");
    }

    @Transactional
    public AcceptanceResponse acceptNew(NewAcceptanceRequest request){
        validateTokenRequest(request==null?null:new PreviewRequest(request.token()));validatePassword(request.password(),request.confirmation());
        Stored invitation=discover(request.token());lockStaff(invitation.organisationId(),invitation.schoolId(),invitation.staffId());
        invitation=byToken(tokens.hash(request.token()),true);requirePublicOpen(invitation);requireIssuer(invitation);Role selected=requireFrozenRole(invitation);
        StaffAccountCandidate candidate=requireCurrentCandidate(invitation);
        lockEmail(invitation.email());
        if(users.findByEmailNormalized(invitation.email()).isPresent())throw conflict("SIGN_IN_REQUIRED","Sign in normally to the invited account before accepting this invitation.");
        UserAccount account=UserAccount.active(invitation.email(),candidate.displayName(),passwordEncoder.encode(request.password()));
        account.choosePreferredLanguage(candidate.language());account=users.saveAndFlush(account);
        return accept(invitation,candidate,selected,account,null);
    }

    @Transactional
    public AcceptanceResponse acceptExisting(AuthenticatedUser user,ExistingAcceptanceRequest request){
        validateTokenRequest(request);
        if(user==null||user.userId()==null||user.sessionId()==null)throw denied("ACCOUNT_MISMATCH","Sign in normally to the invited account.");
        Stored invitation=discover(request.token());lockStaff(invitation.organisationId(),invitation.schoolId(),invitation.staffId());
        invitation=byToken(tokens.hash(request.token()),true);requirePublicOpen(invitation);requireIssuer(invitation);Role selected=requireFrozenRole(invitation);
        StaffAccountCandidate candidate=requireCurrentCandidate(invitation);
        lockEmail(invitation.email());
        UserAccount account=users.findByEmailNormalized(invitation.email()).orElseThrow(()->denied("ACCOUNT_MISMATCH","Sign in normally to the invited account."));
        if(!account.getId().equals(user.userId())||!account.canAuthenticateAt(clock.instant()))throw denied("ACCOUNT_MISMATCH","Sign in normally to the invited account.");
        UserSession session=sessions.findByIdAndUserId(user.sessionId(),user.userId()).orElseThrow(()->denied("ACCOUNT_MISMATCH","A current account session is required."));
        if(!session.isValidAt(clock.instant()))throw denied("ACCOUNT_MISMATCH","A current account session is required.");
        if(activeFactor(user.userId())&&(!user.mfaVerified()||!session.isMfaVerified()))throw denied("MFA_REQUIRED","Sign in with the account's enabled second factor before accepting staff access.");
        return accept(invitation,candidate,selected,account,user);
    }

    private AcceptanceResponse accept(Stored invitation,StaffAccountCandidate candidate,Role selected,UserAccount account,AuthenticatedUser existing){
        requirePublicOpen(invitation);
        // No suspended/ended/existing grant is reactivated or silently broadened, including a campus-scoped membership.
        Boolean existingMembership=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM memberships WHERE user_id=:userId AND organisation_id=:organisationId AND school_id=:schoolId)",
                parameters(invitation).addValue("userId",account.getId()),Boolean.class);
        if(Boolean.TRUE.equals(existingMembership))throw conflict("MEMBERSHIP_REVIEW_REQUIRED","An existing school membership requires a separate reviewed access change.");
        Instant now=clock.instant();UUID membershipId=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,valid_until,created_at,updated_at,row_version)
                VALUES(:membership,:userId,:organisationId,:schoolId,NULL,'ACTIVE',:now,NULL,:now,:now,0)
                """,parameters(invitation).addValue("membership",membershipId).addValue("userId",account.getId()).addValue("now",Timestamp.from(now)));
        jdbc.update("INSERT INTO membership_roles(membership_id,role_id) VALUES(:membership,:roleId)",new MapSqlParameterSource("membership",membershipId).addValue("roleId",selected.id()));
        bindUser(account.getId());
        people.linkAcceptedAccount(candidate,account.getId(),membershipId,"Staff invitation accepted");
        jdbc.update("""
                UPDATE staff_account_invitations SET status='ACCEPTED',token_ciphertext=NULL,token_nonce=NULL,accepted_at=:now,
                  accepted_user_id=:userId,accepted_membership_id=:membership,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND status='ISSUED' AND row_version=:version
                """,parameters(invitation).addValue("now",Timestamp.from(now)).addValue("userId",account.getId()).addValue("membership",membershipId));
        Stored accepted=byId(invitation.organisationId(),invitation.schoolId(),invitation.staffId(),invitation.id(),false);
        AuthenticatedUser actor=new AuthenticatedUser(account.getId(),null,account.getLoginIdentifierNormalized(),account.getEmailNormalized(),account.getDisplayName(),false,
                invitation.organisationId(),Set.of(membershipId),Set.copyOf(selected.capabilities()),List.of());
        event(actor,new Actor(invitation.organisationId(),account.getId(),membershipId),accepted,"ACCEPTED",null,null,"Staff invitation accepted",now);
        if(existing!=null){sessions.findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(account.getId()).forEach(session->session.revoke(now));sessions.flush();}
        return new AcceptanceResponse("accepted","sign_in");
    }

    private Actor authorize(AuthenticatedUser user,UUID schoolId,boolean lock){
        if(user==null||user.userId()==null||user.activeOrganisationId()==null||schoolId==null)throw scopeDenied();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT current_app_organisation_id()=:organisationId",new MapSqlParameterSource("organisationId",user.activeOrganisationId()),Boolean.class)))throw scopeDenied();
        List<UUID> memberships=liveAdministrators(user.userId(),user.activeOrganisationId(),schoolId,null,lock);
        if(memberships.isEmpty())throw scopeDenied();
        if(activeFactor(user.userId())&&!user.mfaVerified())throw denied("MFA_REQUIRED","Sign in with the enabled second factor before managing staff invitations.");
        return new Actor(user.activeOrganisationId(),user.userId(),memberships.getFirst());
    }

    private List<UUID> liveAdministrators(UUID userId,UUID organisationId,UUID schoolId,UUID membershipId,boolean lock){
        return jdbc.query("""
                SELECT membership.id FROM memberships membership
                JOIN user_accounts account ON account.id=membership.user_id
                JOIN schools school ON school.id=membership.school_id AND school.organisation_id=membership.organisation_id
                JOIN organisations organisation ON organisation.id=membership.organisation_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                  AND (CAST(:membershipId AS uuid) IS NULL OR membership.id=CAST(:membershipId AS uuid))
                  AND membership.status='ACTIVE' AND membership.valid_from<=:now AND (membership.valid_until IS NULL OR membership.valid_until>:now)
                  AND account.status='ACTIVE' AND (account.locked_until IS NULL OR account.locked_until<=:now)
                  AND school.status='ACTIVE' AND organisation.status='ACTIVE'
                  AND EXISTS(SELECT 1 FROM membership_roles mapping JOIN roles role ON role.id=mapping.role_id JOIN role_capabilities capability ON capability.role_id=role.id
                    WHERE mapping.membership_id=membership.id AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id) AND capability.capability_code='identity.manage')
                  AND EXISTS(SELECT 1 FROM membership_roles mapping JOIN roles role ON role.id=mapping.role_id JOIN role_capabilities capability ON capability.role_id=role.id
                    WHERE mapping.membership_id=membership.id AND (role.organisation_id IS NULL OR role.organisation_id=membership.organisation_id) AND capability.capability_code='people.manage')
                ORDER BY membership.id
                """+(lock?" FOR SHARE OF membership,account,school":""),new MapSqlParameterSource("userId",userId).addValue("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("membershipId",membershipId,java.sql.Types.OTHER).addValue("now",Timestamp.from(clock.instant())),(row,index)->row.getObject(1,UUID.class));
    }

    private void requireIssuer(Stored invitation){
        if(liveAdministrators(invitation.creatorUserId(),invitation.organisationId(),invitation.schoolId(),invitation.creatorMembershipId(),true).size()!=1)throw changed();
    }
    private boolean activeFactor(UUID userId){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM mfa_totp_factors WHERE user_id=:userId AND status='ACTIVE')",new MapSqlParameterSource("userId",userId),Boolean.class));}

    private StaffAccountCandidate requireCurrentCandidate(Stored invitation){
        StaffAccountCandidate expected=new StaffAccountCandidate(invitation.staffId(),invitation.organisationId(),invitation.schoolId(),invitation.schoolName(),invitation.staffName(),invitation.email(),invitation.language(),invitation.staffVersion(),invitation.joinedOn(),null,"ACTIVE",null);
        StaffAccountCandidate actual;
        try{actual=people.lockCandidate(invitation.organisationId(),invitation.schoolId(),invitation.staffId());}
        catch(ApiException failure){throw changed();}
        if(!expected.equals(actual))throw changed();
        return actual;
    }
    private Role requireFrozenRole(Stored invitation){
        Role current=role(invitation.roleCode());
        if(!current.id().equals(invitation.roleId())||!current.name().equals(invitation.roleName())||!current.capabilities().equals(invitation.capabilities())||!current.hash().equals(invitation.roleHash()))throw changed();
        return current;
    }
    private Role role(String code){
        if(!ROLE_CODES.contains(code))throw roleInvalid();
        List<Role> found=jdbc.query("SELECT id,code,display_name FROM roles WHERE code=:code AND organisation_id IS NULL AND system_role",new MapSqlParameterSource("code",code),(row,index)->{
            UUID id=row.getObject("id",UUID.class);
            List<String> capabilities=jdbc.query("SELECT capability_code FROM role_capabilities WHERE role_id=:roleId ORDER BY capability_code",new MapSqlParameterSource("roleId",id),(capability,n)->capability.getString(1));
            return new Role(id,row.getString("code"),row.getString("display_name"),capabilities,tokens.hash(String.join("\n",capabilities)));
        });
        if(found.size()!=1)throw roleInvalid();return found.getFirst();
    }

    private Stored discover(String raw){
        if(raw==null||!raw.matches("[A-Za-z0-9_-]{43}"))throw invalid();String hash=tokens.hash(raw);
        jdbc.getJdbcTemplate().queryForObject("SELECT set_config('app.current_staff_invitation_token_hash',?,true)",String.class,hash);
        Stored invitation=byToken(hash,false);requirePublicOpen(invitation);
        jdbc.getJdbcTemplate().queryForObject("SELECT set_config('app.current_organisation_id',?,true)",String.class,invitation.organisationId().toString());
        return invitation;
    }
    private Stored byToken(String hash,boolean lock){
        List<Stored> found=jdbc.query("SELECT "+COLUMNS+" FROM staff_account_invitations WHERE token_hash=:hash AND status='ISSUED'"+(lock?" FOR UPDATE":""),new MapSqlParameterSource("hash",hash),this::stored);
        if(found.size()!=1)throw invalid();return found.getFirst();
    }
    private Stored byId(UUID organisationId,UUID schoolId,UUID staffId,UUID id,boolean lock){
        List<Stored> found=jdbc.query("SELECT "+COLUMNS+" FROM staff_account_invitations WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND staff_profile_id=:staffId"+(lock?" FOR UPDATE":""),scope(organisationId,schoolId,staffId).addValue("id",id),this::stored);
        if(found.size()!=1)throw new ApiException(HttpStatus.NOT_FOUND,"STAFF_INVITATION_NOT_FOUND","The staff invitation is unavailable in this school.");return found.getFirst();
    }
    private void requireOpen(Stored invitation){if(!"ISSUED".equals(invitation.status())||!invitation.expiresAt().isAfter(clock.instant()))throw conflict("NOT_OPEN","Only an unexpired open staff invitation supports this action.");}
    private void requirePublicOpen(Stored invitation){if(!"ISSUED".equals(invitation.status())||!"HANDOFF_REVEALED".equals(invitation.deliveryStatus())||!invitation.expiresAt().isAfter(clock.instant()))throw invalid();}

    private Stored terminate(AuthenticatedUser user,Actor actor,Stored invitation,String status,String reason,UUID requestId,String fingerprint,Instant now){
        jdbc.update("""
                UPDATE staff_account_invitations SET status=:status,delivery_status='CANCELLED',token_ciphertext=NULL,token_nonce=NULL,
                  revoked_at=CASE WHEN :status='REVOKED' THEN CAST(:now AS timestamptz) ELSE NULL::timestamptz END,
                  revoked_by_membership_id=CASE WHEN :status='REVOKED' THEN CAST(:actor AS uuid) ELSE NULL::uuid END,
                  revoked_reason=CASE WHEN :status='REVOKED' THEN :reason ELSE NULL END,
                  updated_at=:now,row_version=row_version+1 WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND status='ISSUED' AND row_version=:version
                """,parameters(invitation).addValue("status",status).addValue("now",Timestamp.from(now)).addValue("actor",actor.membershipId()).addValue("reason",reason));
        Stored updated=byId(invitation.organisationId(),invitation.schoolId(),invitation.staffId(),invitation.id(),false);
        event(user,actor,updated,status,requestId,fingerprint,reason,now);return updated;
    }
    private void event(AuthenticatedUser user,Actor actor,Stored invitation,String type,UUID requestId,String fingerprint,String reason,Instant now){
        jdbc.update("""
                INSERT INTO staff_account_invitation_events(id,organisation_id,school_id,invitation_id,request_id,request_fingerprint,event_type,actor_user_id,actor_membership_id,reason,occurred_at,invitation_version)
                VALUES(:eventId,:organisationId,:schoolId,:id,:requestId,:fingerprint,:type,:userId,:membership,:reason,:now,:version)
                """,parameters(invitation).addValue("eventId",UUID.randomUUID()).addValue("requestId",requestId).addValue("fingerprint",fingerprint).addValue("type",type)
                .addValue("userId",actor.userId()).addValue("membership",actor.membershipId()).addValue("reason",reason).addValue("now",Timestamp.from(now)));
        audit.recordScoped(user,actor.membershipId(),invitation.schoolId(),"identity.staff_invitation."+type.toLowerCase(java.util.Locale.ROOT),"STAFF_ACCOUNT_INVITATION",invitation.id().toString(),AuditOutcome.SUCCEEDED,reason,
                Map.of("staffId",invitation.staffId(),"roleCode",invitation.roleCode(),"invitationVersion",invitation.version(),"deliveryStatus",invitation.deliveryStatus()));
    }
    private RequestEvent requestEvent(UUID organisationId,UUID requestId){
        List<RequestEvent> found=jdbc.query("SELECT invitation_id,event_type,request_fingerprint FROM staff_account_invitation_events WHERE organisation_id=:organisationId AND request_id=:requestId",
                new MapSqlParameterSource("organisationId",organisationId).addValue("requestId",requestId),(row,index)->new RequestEvent(row.getObject("invitation_id",UUID.class),row.getString("event_type"),row.getString("request_fingerprint")));
        return found.isEmpty()?null:found.getFirst();
    }
    private static void requireReplay(RequestEvent actual,UUID id,String type,String fingerprint){if(!actual.invitationId().equals(id)||!actual.type().equals(type)||!actual.fingerprint().equals(fingerprint))throw requestConflict();}
    private void lockStaff(UUID organisationId,UUID schoolId,UUID staffId){if(staffId==null)throw terms();lock("staff-account-invitation:"+organisationId+":"+schoolId+":"+staffId);}
    private void lockEmail(String email){lock("staff-account-invitation-email:"+email);}
    private void lock(String key){jdbc.getJdbcTemplate().queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) AS locked",Integer.class,key);}
    private void bindUser(UUID userId){jdbc.getJdbcTemplate().queryForObject("SELECT set_config('app.current_user_id',?,true)",String.class,userId.toString());}

    private Invitation response(Stored stored){
        boolean open="ISSUED".equals(stored.status())&&stored.expiresAt().isAfter(clock.instant());String status="ISSUED".equals(stored.status())&&!open?"EXPIRED":stored.status();
        return new Invitation(stored.id(),stored.schoolId(),stored.staffId(),stored.roleCode(),stored.roleName(),maskEmail(stored.email()),status,stored.deliveryStatus(),stored.expiresAt(),stored.createdAt(),stored.acceptedAt(),stored.revokedAt(),stored.handoffAt(),stored.version(),open,open&&manualHandoffEnabled&&stored.handoffAt()==null);
    }
    static String maskEmail(String email){int at=email.indexOf('@');return at<1?"***":email.substring(0,1)+"***@"+email.substring(at+1);}
    static void validatePassword(String password,String confirmation){
        if(password==null||password.length()<16||password.length()>200||password.isBlank()||password.chars().anyMatch(Character::isISOControl)||!password.equals(confirmation))
            throw new ApiException(HttpStatus.BAD_REQUEST,"STAFF_INVITATION_PASSWORD_INVALID","Choose matching passwords of 16–200 non-control characters.");
    }
    private void validate(Object request){if(request==null||!validator.validate(request).isEmpty())throw terms();}
    private void validateTokenRequest(Object request){if(request==null||!validator.validate(request).isEmpty())throw invalid();}
    private static String reason(String value){if(value==null)throw terms();String clean=value.strip();if(clean.length()<3||clean.length()>240||clean.chars().anyMatch(Character::isISOControl))throw terms();return clean;}
    private String fingerprint(Object... fields){return tokens.hash(Arrays.stream(fields).map(String::valueOf).collect(java.util.stream.Collectors.joining("|","staff-invitation:v1|","")));}
    private static MapSqlParameterSource scope(UUID organisationId,UUID schoolId,UUID staffId){return new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId).addValue("staffId",staffId);}
    private static MapSqlParameterSource parameters(Stored stored){return scope(stored.organisationId(),stored.schoolId(),stored.staffId()).addValue("id",stored.id()).addValue("version",stored.version());}
    private Stored stored(ResultSet row,int index)throws SQLException{
        Object[] values=(Object[])row.getArray("role_capabilities").getArray();List<String> capabilities=Arrays.stream(values).map(String::valueOf).toList();
        return new Stored(row.getObject("id",UUID.class),row.getObject("organisation_id",UUID.class),row.getObject("school_id",UUID.class),row.getObject("staff_profile_id",UUID.class),row.getObject("request_id",UUID.class),row.getString("request_fingerprint"),row.getLong("staff_version"),row.getString("staff_name"),row.getString("school_name"),row.getString("target_email_normalized"),row.getString("staff_language"),row.getObject("staff_joined_on",LocalDate.class),row.getObject("role_id",UUID.class),row.getString("role_code"),row.getString("role_name"),capabilities,row.getString("role_capabilities_hash"),row.getString("token_hash"),row.getString("token_ciphertext"),row.getString("token_nonce"),row.getString("status"),row.getString("delivery_status"),instant(row,"expires_at"),row.getObject("created_by_user_id",UUID.class),row.getObject("created_by_membership_id",UUID.class),instant(row,"created_at"),instant(row,"accepted_at"),instant(row,"revoked_at"),instant(row,"handoff_at"),row.getLong("row_version"));
    }
    private static Instant instant(ResultSet row,String name)throws SQLException{Timestamp value=row.getTimestamp(name);return value==null?null:value.toInstant();}
    private static ApiException invalid(){return new ApiException(HttpStatus.GONE,"STAFF_INVITATION_INVALID",INVALID_MESSAGE);}
    private static ApiException changed(){return conflict("CONTEXT_CHANGED","The invitation, staff profile or issuing authority changed. Ask the school to review and reissue it.");}
    private static ApiException roleInvalid(){return new ApiException(HttpStatus.BAD_REQUEST,"STAFF_INVITATION_ROLE_INVALID","Select an available existing school staff role.");}
    private static ApiException terms(){return new ApiException(HttpStatus.BAD_REQUEST,"STAFF_INVITATION_TERMS_INVALID","One or more staff invitation values are invalid.");}
    private static ApiException scopeDenied(){return denied("SCOPE_DENIED","Staff invitation management is unavailable in this school context.");}
    private static ApiException handoffAlreadyRevealed(){return conflict("HANDOFF_ALREADY_REVEALED","The setup link was already revealed. If it was lost, revoke and reissue the invitation.");}
    private static ApiException requestConflict(){return conflict("REQUEST_CONFLICT","This request identity was already used with different invitation terms.");}
    private static ApiException conflict(String suffix,String message){return new ApiException(HttpStatus.CONFLICT,"STAFF_INVITATION_"+suffix,message);}
    private static ApiException denied(String suffix,String message){return new ApiException(HttpStatus.FORBIDDEN,"STAFF_INVITATION_"+suffix,message);}
    private record Actor(UUID organisationId,UUID userId,UUID membershipId){}
    private record Role(UUID id,String code,String name,List<String> capabilities,String hash){}
    private record RequestEvent(UUID invitationId,String type,String fingerprint){}
    private record Stored(UUID id,UUID organisationId,UUID schoolId,UUID staffId,UUID requestId,String requestFingerprint,
            long staffVersion,String staffName,String schoolName,String email,String language,LocalDate joinedOn,UUID roleId,String roleCode,String roleName,List<String> capabilities,String roleHash,
            String tokenHash,String ciphertext,String nonce,String status,String deliveryStatus,Instant expiresAt,UUID creatorUserId,UUID creatorMembershipId,Instant createdAt,
            Instant acceptedAt,Instant revokedAt,Instant handoffAt,long version){
        @Override public String toString(){return "StaffInvitationStored[redacted]";}
    }
}
