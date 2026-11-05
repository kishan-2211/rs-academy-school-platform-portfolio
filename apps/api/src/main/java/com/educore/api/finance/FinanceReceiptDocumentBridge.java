package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Narrow official-document read boundary; bank verification and review evidence never leave finance. */
@Service
public class FinanceReceiptDocumentBridge {
    private final NamedParameterJdbcTemplate jdbc;

    public FinanceReceiptDocumentBridge(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called only after the document service has resolved its real school-scoped actor. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public ReceiptTiming receiptTiming(UUID actorUserId, UUID organisationId, UUID schoolId, UUID paymentId,
                                       UUID documentActorMembershipId) {
        if (actorUserId == null || organisationId == null || schoolId == null || paymentId == null
                || documentActorMembershipId == null
                || !TenantRequestContext.current().map(scope -> actorUserId.equals(scope.userId())
                    && organisationId.equals(scope.organisationId())).orElse(false)) {
            throw unavailable();
        }
        var rows = jdbc.query("""
                SELECT payment.received_on AS posting_on,
                  COALESCE(manual.original_received_on,capture.original_business_date,payment.received_on) AS original_received_on,
                  COALESCE(manual.actual_received_at,capture.paid_at) AS actual_received_at,
                  capture.paid_at_exact AS actual_received_at_exact,
                  COALESCE(manual.received_time_precision,CASE WHEN capture.paid_at IS NOT NULL THEN 'INSTANT' ELSE 'DATE_ONLY' END) AS received_time_precision,
                  COALESCE(manual.school_time_zone,attempt.school_time_zone) AS received_time_zone
                FROM fee_payments payment
                LEFT JOIN fee_manual_collection_evidence manual ON manual.fee_payment_id=payment.id
                  AND manual.organisation_id=payment.organisation_id AND manual.school_id=payment.school_id
                LEFT JOIN fee_gateway_captures capture ON capture.id=payment.gateway_capture_id
                  AND capture.organisation_id=payment.organisation_id AND capture.school_id=payment.school_id
                LEFT JOIN fee_gateway_attempts attempt ON attempt.id=capture.attempt_id
                  AND attempt.organisation_id=capture.organisation_id AND attempt.school_id=capture.school_id
                WHERE payment.id=:paymentId AND payment.organisation_id=:organisationId
                  AND payment.school_id=:schoolId AND payment.status='POSTED'
                  AND current_app_user_id()=:actorUserId AND current_app_organisation_id()=:organisationId
                  AND EXISTS (
                    SELECT 1 FROM memberships member
                    JOIN user_accounts actor ON actor.id=member.user_id AND actor.status='ACTIVE'
                    JOIN organisations organisation ON organisation.id=member.organisation_id AND organisation.status='ACTIVE'
                    JOIN schools school ON school.id=:schoolId AND school.organisation_id=member.organisation_id
                      AND school.status='ACTIVE'
                    WHERE member.id=:actorMembershipId AND member.user_id=:actorUserId AND member.organisation_id=:organisationId
                      AND (member.school_id=school.id OR member.school_id IS NULL)
                      AND member.status='ACTIVE' AND member.valid_from<=clock_timestamp()
                      AND (member.valid_until IS NULL OR member.valid_until>clock_timestamp())
                      AND EXISTS (SELECT 1 FROM membership_roles assignment JOIN roles role ON role.id=assignment.role_id
                        AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                        JOIN role_capabilities grant_record ON grant_record.role_id=role.id
                        WHERE assignment.membership_id=member.id AND grant_record.capability_code='fees.read')
                      AND EXISTS (SELECT 1 FROM membership_roles assignment JOIN roles role ON role.id=assignment.role_id
                        AND (role.organisation_id IS NULL OR role.organisation_id=member.organisation_id)
                        JOIN role_capabilities grant_record ON grant_record.role_id=role.id
                        WHERE assignment.membership_id=member.id
                          AND grant_record.capability_code IN ('documents.issue','documents.approve'))
                  )
                """, new MapSqlParameterSource("organisationId", organisationId).addValue("schoolId", schoolId)
                .addValue("paymentId", paymentId).addValue("actorMembershipId", documentActorMembershipId)
                .addValue("actorUserId", actorUserId),
                (result, row) -> new ReceiptTiming(result.getObject("posting_on", LocalDate.class),
                        result.getObject("original_received_on", LocalDate.class),
                        GatewayExactInstant.readNullable(result,"actual_received_at_exact","actual_received_at"),
                        result.getString("received_time_precision"), result.getString("received_time_zone")));
        if (rows.size() != 1) throw unavailable();
        return rows.getFirst();
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_RECEIPT_SOURCE_UNAVAILABLE",
                "The current fee receipt timing is unavailable within this authorized school work.");
    }

    public record ReceiptTiming(LocalDate postingOn, LocalDate originalReceivedOn, Instant actualReceivedAt,
                                String receivedTimePrecision, String schoolTimeZone) {
        public ReceiptTiming {
            if (postingOn == null || originalReceivedOn == null || postingOn.isBefore(originalReceivedOn)
                    || !("INSTANT".equals(receivedTimePrecision) && actualReceivedAt != null
                    || "DATE_ONLY".equals(receivedTimePrecision) && actualReceivedAt == null)) {
                throw new IllegalArgumentException("Stored receipt timing evidence is invalid.");
            }
            if (schoolTimeZone != null) {
                ZoneId zone = ZoneId.of(schoolTimeZone);
                if (actualReceivedAt != null && !actualReceivedAt.atZone(zone).toLocalDate().equals(originalReceivedOn)) {
                    throw new IllegalArgumentException("Stored receipt instant and received day disagree.");
                }
            }
        }
    }
}
