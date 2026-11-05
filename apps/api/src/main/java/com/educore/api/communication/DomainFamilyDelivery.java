package com.educore.api.communication;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared recipient, consent and delivery evidence for database-validated domain publications. */
@Service
public class DomainFamilyDelivery {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public DomainFamilyDelivery(NamedParameterJdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    @Transactional(propagation=Propagation.MANDATORY)
    public void attach(UUID organisationId,UUID schoolId,UUID noticeId,UUID guardianId,UUID learnerId,UUID userId,String reason) {
        if(!TenantRequestContext.current().map(s -> organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Domain delivery requires a matching organisation context");
        var p=new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("id",noticeId).addValue("guardianId",guardianId).addValue("learnerId",learnerId)
                .addValue("userId",userId).addValue("recipientId",UUID.randomUUID()).addValue("now",clock.instant())
                .addValue("kind","GUARDIAN").addValue("membershipId",null);
        attachRecipient(p);
        jdbc.update("""
                INSERT INTO communication_announcement_history(id,organisation_id,school_id,communication_announcement_id,
                  event_type,new_status,reason,actor_kind,occurred_at)
                VALUES(:historyId,:organisationId,:schoolId,:id,'CREATED','APPROVED',:reason,'SYSTEM',:now)
                """,p.addValue("historyId",UUID.randomUUID()).addValue("reason",reason));
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void attachStaff(UUID organisationId,UUID schoolId,UUID noticeId,UUID membershipId,UUID userId) {
        if(!TenantRequestContext.current().map(s -> organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Domain delivery requires a matching organisation context");
        attachRecipient(new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("id",noticeId).addValue("guardianId",null).addValue("learnerId",null)
                .addValue("userId",userId).addValue("recipientId",UUID.randomUUID()).addValue("now",clock.instant())
                .addValue("kind","STAFF").addValue("membershipId",membershipId));
    }

    /** Multi-recipient publishers write their one SYSTEM history after the entire audience. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void attachGuardian(UUID organisationId,UUID schoolId,UUID noticeId,UUID guardianId,UUID learnerId,UUID userId) {
        attachLearningRecipient(organisationId,schoolId,noticeId,"GUARDIAN",guardianId,null,learnerId,userId);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void attachStudent(UUID organisationId,UUID schoolId,UUID noticeId,UUID membershipId,UUID learnerId,UUID userId) {
        attachLearningRecipient(organisationId,schoolId,noticeId,"STUDENT",null,membershipId,learnerId,userId);
    }

    private void attachLearningRecipient(UUID organisationId,UUID schoolId,UUID noticeId,String kind,
            UUID guardianId,UUID membershipId,UUID learnerId,UUID userId) {
        if(!TenantRequestContext.current().map(s->organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Domain delivery requires a matching organisation context");
        attachRecipient(new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("id",noticeId).addValue("guardianId",guardianId).addValue("learnerId",learnerId)
                .addValue("userId",userId).addValue("recipientId",UUID.randomUUID()).addValue("now",clock.instant())
                .addValue("kind",kind).addValue("membershipId",membershipId));
    }

    private void attachRecipient(MapSqlParameterSource p) {
        String category=jdbc.queryForObject("""
                SELECT category FROM communication_announcements WHERE id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId AND authority_kind='DOMAIN_EVENT' AND status='APPROVED'
                """,p,String.class);
        var preferences=jdbc.query("""
                SELECT preferred_language,safe_contact_status,allow_push FROM communication_contact_preferences
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND user_account_id=:userId
                """,p,r -> r.next()?new Preferences(r.getString(1),r.getString(2),r.getBoolean(3)):
                        new Preferences("EN","TRANSACTIONAL_ONLY",false));
        boolean eligible=!"BLOCKED".equals(preferences.safe());
        p.addValue("language",preferences.language()).addValue("eligible",eligible)
                .addValue("suppression",eligible?null:"SAFE_CONTACT_BLOCKED");
        jdbc.update("""
                INSERT INTO communication_recipients(id,organisation_id,school_id,communication_announcement_id,recipient_kind,
                  membership_id,guardian_id,learner_id,user_account_id,preferred_language,contact_eligible,suppression_reason,available_at,created_at)
                SELECT :recipientId,:organisationId,:schoolId,:id,:kind,:membershipId,:guardianId,:learnerId,:userId,:language,:eligible,
                  :suppression,scheduled_for,:now FROM communication_announcements WHERE id=:id
                """,p);
        delivery(p,"IN_APP",eligible?"READY":"SUPPRESSED",null);
        // Student identity has no external-contact channel. Do not create even
        // suppressed push jobs or inspect external providers for student notices.
        if("STUDENT".equals(p.getValue("kind"))) return;
        boolean push=eligible && preferences.push() && CommunicationService.allowsExternalContact(preferences.safe(),category);
        UUID provider=push?jdbc.query("""
                SELECT id FROM communication_provider_configs WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND channel='PUSH' AND environment='LIVE' AND status='ACTIVE' AND provider_code='WEB_PUSH'
                  AND credential_secret_reference='env:APP_PUSH_DELIVERY_PRIVATE_KEY'
                """,p,r -> r.next()?r.getObject(1,UUID.class):null):null;
        delivery(p,"PUSH",!push?"SUPPRESSED":provider==null?"AWAITING_PROVIDER":"READY",provider);
    }
    private void delivery(MapSqlParameterSource p,String channel,String status,UUID provider) {
        jdbc.update("""
                INSERT INTO communication_deliveries(id,organisation_id,school_id,communication_recipient_id,channel,status,
                  provider_config_id,created_at,updated_at)
                VALUES(:deliveryId,:organisationId,:schoolId,:recipientId,:channel,:status,:provider,:now,:now)
                """,p.addValue("deliveryId",UUID.randomUUID()).addValue("channel",channel).addValue("status",status).addValue("provider",provider));
    }
    private record Preferences(String language,String safe,boolean push) {}
}
