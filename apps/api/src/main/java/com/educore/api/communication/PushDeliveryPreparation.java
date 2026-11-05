package com.educore.api.communication;

import com.educore.api.tenancy.TenantRequestContext;
import com.educore.api.schoolcalendar.PublishedSchoolCalendarService;
import com.educore.api.ptm.PtmNotificationSource;
import com.educore.api.parentrequests.ParentRequestNotificationSource;
import com.educore.api.studentleave.StudentLeaveNotificationSource;
import com.educore.api.studentleave.StudentLeaveSubmissionNotificationSource;
import com.educore.api.learning.LearningPublicationNotificationSource;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Revalidate immediately before an outbound call; never cache this decision or expose it as an API. */
@Service
public class PushDeliveryPreparation {
    private final NamedParameterJdbcTemplate jdbc;
    private final PushSubscriptionProtector protector;
    private final Clock clock;
    private final PublishedSchoolCalendarService calendars;
    private final PtmNotificationSource ptm;
    private final ParentRequestNotificationSource requests;
    private final StudentLeaveNotificationSource studentLeave;
    private final StudentLeaveSubmissionNotificationSource leaveSubmissions;
    private final LearningPublicationNotificationSource learning;

    public PushDeliveryPreparation(NamedParameterJdbcTemplate jdbc, PushSubscriptionProtector protector, Clock clock,
            PublishedSchoolCalendarService calendars, PtmNotificationSource ptm, ParentRequestNotificationSource requests,
            StudentLeaveNotificationSource studentLeave,StudentLeaveSubmissionNotificationSource leaveSubmissions,
            LearningPublicationNotificationSource learning) {
        this.jdbc=jdbc;
        this.protector=protector;
        this.clock=clock;
        this.calendars=calendars;
        this.ptm=ptm;
        this.requests=requests;
        this.studentLeave=studentLeave;
        this.leaveSubmissions=leaveSubmissions;
        this.learning=learning;
    }

    @Transactional(readOnly=true)
    public Prepared prepare(PushDeliveryQueue.Claim claim) {
        Objects.requireNonNull(claim);
        if (!TenantRequestContext.current().map(s -> claim.organisationId().equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Push preparation requires an explicit matching organisation context");
        Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        var params=new MapSqlParameterSource("organisationId",claim.organisationId()).addValue("schoolId",claim.schoolId())
                .addValue("jobId",claim.jobId()).addValue("token",claim.token()).addValue("deliveryId",claim.deliveryId())
                .addValue("subscriptionId",claim.subscriptionId()).addValue("now",now);
        List<Prepared> rows=jdbc.query("""
                SELECT delivery.status delivery_status,notice.status notice_status,notice.request_push,
                  notice.category,notice.priority,notice.scheduled_for,notice.expires_at notice_expires_at,
                  notice.source_type,notice.source_id,recipient.guardian_id,recipient.learner_id,
                  recipient.membership_id,recipient.user_account_id,
                  job.expires_at job_expires_at,recipient.available_at,recipient.recipient_kind,recipient.contact_eligible,
                  subscription.status subscription_status,subscription.row_version subscription_version,
                  subscription.endpoint_ciphertext,subscription.endpoint_nonce,subscription.p256dh_ciphertext,
                  subscription.p256dh_nonce,subscription.auth_ciphertext,subscription.auth_nonce,
                  preference.allow_push,preference.safe_contact_status,preference.preferred_language,
                  preference.time_zone,preference.quiet_hours_start,preference.quiet_hours_end,
                  provider.status provider_status,provider.channel provider_channel,provider.environment,
                  provider.provider_code,provider.credential_secret_reference,
                  (account.status='ACTIVE' AND school.status='ACTIVE' AND organisation.status='ACTIVE'
                    AND EXISTS (SELECT 1 FROM memberships membership
                      JOIN membership_roles mr ON mr.membership_id=membership.id
                      JOIN roles role ON role.id=mr.role_id
                      JOIN role_capabilities capability ON capability.role_id=role.id
                      WHERE membership.user_id=recipient.user_account_id
                        AND membership.organisation_id=recipient.organisation_id AND membership.school_id=recipient.school_id
                        AND membership.status='ACTIVE' AND membership.valid_from<=:now
                        AND (membership.valid_until IS NULL OR membership.valid_until>:now)
                        AND (role.organisation_id IS NULL OR role.organisation_id=recipient.organisation_id)
                        AND ((recipient.recipient_kind='STAFF' AND membership.id=recipient.membership_id
                              AND capability.capability_code='communication.read')
                          OR (recipient.recipient_kind='GUARDIAN' AND capability.capability_code='portal.guardian')))
                    AND (recipient.recipient_kind='STAFF' OR (recipient.recipient_kind='GUARDIAN' AND EXISTS (
                      SELECT 1 FROM learner_guardian_relationships relationship
                      JOIN guardians guardian ON guardian.id=relationship.guardian_id
                        AND guardian.organisation_id=relationship.organisation_id AND guardian.school_id=relationship.school_id
                      JOIN learners learner ON learner.id=relationship.learner_id
                        AND learner.organisation_id=relationship.organisation_id AND learner.school_id=relationship.school_id
                      WHERE relationship.organisation_id=recipient.organisation_id AND relationship.school_id=recipient.school_id
                        AND relationship.guardian_id=recipient.guardian_id AND relationship.learner_id=recipient.learner_id
                        AND relationship.portal_access_status='ACTIVE' AND guardian.status='ACTIVE' AND learner.status='ACTIVE'
                        AND guardian.user_account_id=recipient.user_account_id
                        AND EXISTS (SELECT 1 FROM student_enrolments enrolment
                          WHERE enrolment.organisation_id=recipient.organisation_id AND enrolment.school_id=recipient.school_id
                            AND enrolment.learner_id=recipient.learner_id AND enrolment.status='ACTIVE'))))) recipient_active
                FROM communication_push_jobs job
                JOIN communication_deliveries delivery ON delivery.id=job.communication_delivery_id
                  AND delivery.organisation_id=job.organisation_id AND delivery.school_id=job.school_id
                JOIN communication_recipients recipient ON recipient.id=delivery.communication_recipient_id
                  AND recipient.organisation_id=job.organisation_id AND recipient.school_id=job.school_id
                JOIN communication_announcements notice ON notice.id=recipient.communication_announcement_id
                  AND notice.organisation_id=job.organisation_id AND notice.school_id=job.school_id
                JOIN communication_push_subscriptions subscription ON subscription.id=job.communication_push_subscription_id
                  AND subscription.organisation_id=job.organisation_id AND subscription.school_id=job.school_id
                  AND subscription.user_account_id=recipient.user_account_id
                JOIN user_accounts account ON account.id=recipient.user_account_id
                JOIN schools school ON school.id=job.school_id AND school.organisation_id=job.organisation_id
                JOIN organisations organisation ON organisation.id=job.organisation_id
                LEFT JOIN communication_contact_preferences preference ON preference.organisation_id=job.organisation_id
                  AND preference.school_id=job.school_id AND preference.user_account_id=recipient.user_account_id
                LEFT JOIN communication_provider_configs provider ON provider.id=delivery.provider_config_id
                  AND provider.organisation_id=job.organisation_id AND provider.school_id=job.school_id
                WHERE job.id=:jobId AND job.organisation_id=:organisationId AND job.school_id=:schoolId
                  AND job.lease_token=:token AND job.status='LEASED' AND job.lease_until>:now
                  AND delivery.id=:deliveryId AND delivery.channel='PUSH' AND subscription.id=:subscriptionId
                """,params,(r,n) -> {
            Instant expiry=r.getTimestamp("job_expires_at").toInstant();
            if(r.getTimestamp("notice_expires_at")!=null && r.getTimestamp("notice_expires_at").toInstant().isBefore(expiry))
                expiry=r.getTimestamp("notice_expires_at").toInstant();
            if(!expiry.isAfter(now) || !"APPROVED".equals(r.getString("notice_status")) || !r.getBoolean("request_push")
                    || !List.of("READY","QUEUED","SENT").contains(r.getString("delivery_status")))
                return cancel("PUSH_SOURCE_UNAVAILABLE");
            String source=r.getString("source_type");
            if("PTM_REMINDER".equals(source)) {
                var current=ptm.current(claim.organisationId(),claim.schoolId(),r.getObject("source_id",java.util.UUID.class));
                if(current==null || !current.guardianId().equals(r.getObject("guardian_id",java.util.UUID.class))
                        || !current.learnerId().equals(r.getObject("learner_id",java.util.UUID.class)))
                    return cancel("PUSH_SOURCE_UNAVAILABLE");
            } else if("PARENT_REQUEST_UPDATE".equals(source)) {
                var current=requests.current(claim.organisationId(),claim.schoolId(),r.getObject("source_id",java.util.UUID.class));
                if(current==null || !current.guardianId().equals(r.getObject("guardian_id",java.util.UUID.class))
                        || !current.learnerId().equals(r.getObject("learner_id",java.util.UUID.class)))
                    return cancel("PUSH_SOURCE_UNAVAILABLE");
            } else if("STUDENT_LEAVE_UPDATE".equals(source)) {
                var current=studentLeave.current(claim.organisationId(),claim.schoolId(),r.getObject("source_id",java.util.UUID.class));
                if(current==null || !current.guardianId().equals(r.getObject("guardian_id",java.util.UUID.class))
                        || !current.learnerId().equals(r.getObject("learner_id",java.util.UUID.class)))
                    return cancel("PUSH_SOURCE_UNAVAILABLE");
            } else if("STUDENT_LEAVE_SUBMISSION".equals(source)) {
                if(!leaveSubmissions.isCurrentReviewer(claim.organisationId(),claim.schoolId(),
                        r.getObject("source_id",java.util.UUID.class),r.getObject("membership_id",java.util.UUID.class),
                        r.getObject("user_account_id",java.util.UUID.class))) return cancel("PUSH_SOURCE_UNAVAILABLE");
            } else if("ASSIGNMENT_PUBLICATION".equals(source)) {
                if(!learning.isCurrentGuardian(claim.organisationId(),claim.schoolId(),
                        r.getObject("source_id",java.util.UUID.class),r.getObject("guardian_id",java.util.UUID.class),
                        r.getObject("learner_id",java.util.UUID.class),r.getObject("user_account_id",java.util.UUID.class)))
                    return cancel("PUSH_SOURCE_UNAVAILABLE");
            } else if(source!=null && (!"SCHOOL_CALENDAR_NOTICE".equals(source)
                    || !calendars.isCurrentNotificationRevision(claim.organisationId(),claim.schoolId(),
                        r.getObject("source_id",java.util.UUID.class)))) return cancel("PUSH_SOURCE_UNAVAILABLE");
            if(!r.getBoolean("recipient_active") || !r.getBoolean("contact_eligible")
                    || !"ACTIVE".equals(r.getString("subscription_status"))) return cancel("PUSH_RECIPIENT_UNAVAILABLE");
            if(!r.getBoolean("allow_push") || !CommunicationService.allowsExternalContact(
                    r.getString("safe_contact_status"),r.getString("category"))) return cancel("PUSH_CONSENT_WITHDRAWN");
            Instant available=r.getTimestamp("available_at").toInstant();
            if(r.getTimestamp("scheduled_for").toInstant().isAfter(available)) available=r.getTimestamp("scheduled_for").toInstant();
            if(available.isAfter(now)) return defer("PUSH_NOT_DUE",secondsUntil(now,available));
            if(!"ACTIVE".equals(r.getString("provider_status")) || !"PUSH".equals(r.getString("provider_channel"))
                    || !"LIVE".equals(r.getString("environment")) || !"WEB_PUSH".equals(r.getString("provider_code"))
                    || !"env:APP_PUSH_DELIVERY_PRIVATE_KEY".equals(r.getString("credential_secret_reference")))
                return defer("PUSH_PROVIDER_NOT_READY",300);
            boolean emergency="EMERGENCY".equals(r.getString("category")) && "EMERGENCY".equals(r.getString("priority"));
            try {
                long quiet=quietDelay(now,r.getString("time_zone"),r.getObject("quiet_hours_start",LocalTime.class),
                        r.getObject("quiet_hours_end",LocalTime.class),emergency);
                if(quiet>0) return defer("PUSH_QUIET_HOURS",quiet);
            } catch(DateTimeException | IllegalArgumentException invalidZone) {
                return defer("PUSH_PREFERENCE_INVALID",300);
            }
            // Lock-screen content is deliberately generic: no names, marks, balances, or private notice text.
            boolean hindi="HI".equals(r.getString("preferred_language"));
            String payload=hindi
                    ? "{\"title\":\"स्कूल की नई सूचना\",\"body\":\"विवरण देखने के लिए सुरक्षित स्कूल इनबॉक्स खोलें।\",\"url\":\"/portal\"}"
                    : "{\"title\":\"New school notice\",\"body\":\"Open the secure school inbox for details.\",\"url\":\"/portal\"}";
            try {
                var endpoint=new PushSubscriptionProtector.ProtectedValue(r.getString("endpoint_ciphertext"),r.getString("endpoint_nonce"));
                var p256dh=new PushSubscriptionProtector.ProtectedValue(r.getString("p256dh_ciphertext"),r.getString("p256dh_nonce"));
                var auth=new PushSubscriptionProtector.ProtectedValue(r.getString("auth_ciphertext"),r.getString("auth_nonce"));
                return new Prepared(Action.SEND,"PUSH_READY",0,new PushTransport.Message(
                        protector.reveal(claim.subscriptionId(),"endpoint",endpoint),protector.reveal(claim.subscriptionId(),"p256dh",p256dh),
                        protector.reveal(claim.subscriptionId(),"auth",auth),payload,claim.jobId().toString().replace("-",""),emergency,expiry),
                        r.getLong("subscription_version"));
            } catch(RuntimeException unavailableKey) { return defer("PUSH_DECRYPTION_UNAVAILABLE",300); }
        });
        return rows.isEmpty() ? cancel("PUSH_CLAIM_UNAVAILABLE") : rows.getFirst();
    }

    static long quietDelay(Instant now,String zone,LocalTime start,LocalTime end,boolean approvedEmergency) {
        if(approvedEmergency || start==null && end==null) return 0;
        if(start==null || end==null || start.equals(end)) throw new IllegalArgumentException("Invalid quiet-hours window");
        var local=now.atZone(ZoneId.of(zone));
        var time=local.toLocalTime();
        boolean overnight=start.isAfter(end);
        boolean quiet=overnight ? !time.isBefore(start) || time.isBefore(end) : !time.isBefore(start) && time.isBefore(end);
        if(!quiet) return 0;
        var date=overnight && !time.isBefore(start) ? local.toLocalDate().plusDays(1) : local.toLocalDate();
        return secondsUntil(now,date.atTime(end).atZone(local.getZone()).withLaterOffsetAtOverlap().toInstant());
    }

    private static long secondsUntil(Instant now,Instant end) {
        Duration delay=Duration.between(now,end);
        return Math.max(1,Math.min(86400,delay.getSeconds()+(delay.getNano()>0?1:0)));
    }
    private static Prepared cancel(String code) { return new Prepared(Action.CANCEL,code,0,null,-1); }
    private static Prepared defer(String code,long seconds) { return new Prepared(Action.DEFER,code,seconds,null,-1); }
    public enum Action { SEND,CANCEL,DEFER }
    public record Prepared(Action action,String safeCode,long retryAfterSeconds,PushTransport.Message message,long subscriptionVersion) {
        @Override public String toString() { return "PreparedPush["+action+","+safeCode+",redacted]"; }
    }
}
