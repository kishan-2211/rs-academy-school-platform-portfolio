package com.educore.api.documents;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Family reads never grant staff issuance permissions or expose document snapshots/storage keys. */
@Service
public class FamilyDocumentService {
    private static final int PAGE_SIZE = 25;
    private static final String ELIGIBLE = """
            FROM official_documents d
            WHERE d.organisation_id=:organisationId AND d.school_id=:schoolId
              AND d.subject_type='LEARNER' AND d.subject_id=:learnerId AND d.status='ISSUED'
              AND d.pdf_size_bytes>0 AND d.storage_key IS NOT NULL
              AND (
                (d.document_kind='FEE_RECEIPT' AND d.source_type='FEE_PAYMENT' AND EXISTS (
                  SELECT 1 FROM fee_payments p JOIN learner_fee_accounts a
                    ON a.id=p.learner_fee_account_id AND a.organisation_id=p.organisation_id
                    AND a.school_id=p.school_id
                  WHERE p.id=d.source_id AND p.organisation_id=d.organisation_id
                    AND p.school_id=d.school_id AND p.status='POSTED' AND a.learner_id=:learnerId))
                OR (d.document_kind='REPORT_CARD' AND d.source_type='LEARNER_REPORT_CARD' AND EXISTS (
                  SELECT 1 FROM learner_report_cards c JOIN result_publications p
                    ON p.id=c.result_publication_id AND p.organisation_id=c.organisation_id
                    AND p.school_id=c.school_id
                  WHERE c.id=d.source_id AND c.organisation_id=d.organisation_id
                    AND c.school_id=d.school_id AND c.learner_id=:learnerId AND p.status='PUBLISHED'))
                OR (d.document_kind='BONAFIDE_CERTIFICATE' AND d.source_type='STUDENT_ENROLMENT' AND EXISTS (
                  SELECT 1 FROM student_enrolments e WHERE e.id=d.source_id
                    AND e.organisation_id=d.organisation_id AND e.school_id=d.school_id AND e.learner_id=:learnerId))
              )
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final OfficialDocumentObjectStore objectStore;
    private final SecurityAuditService audit;
    private final Clock clock;

    public FamilyDocumentService(NamedParameterJdbcTemplate jdbc, OfficialDocumentObjectStore objectStore,
                                 SecurityAuditService audit, Clock clock) {
        this.jdbc=jdbc; this.objectStore=objectStore; this.audit=audit; this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Page list(AuthenticatedUser user, UUID learnerId, int page) {
        return list(user,learnerId,page,null);
    }

    @Transactional(readOnly=true)
    public Page list(AuthenticatedUser user, UUID learnerId, int page, UUID paymentId) {
        if(page<0 || page>10000) throw new ApiException(HttpStatus.BAD_REQUEST,
                "DOCUMENT_PAGE_INVALID", "Choose a valid document page.");
        Scope scope=scope(user,learnerId);
        if(paymentId!=null) {
            var payments=jdbc.query("""
                    SELECT p.id FROM fee_payments p JOIN learner_fee_accounts a
                      ON a.id=p.learner_fee_account_id AND a.organisation_id=p.organisation_id AND a.school_id=p.school_id
                    WHERE p.id=:paymentId AND p.organisation_id=:organisationId AND p.school_id=:schoolId
                      AND a.learner_id=:learnerId AND current_app_user_id()=:userId
                      AND current_app_organisation_id()=:organisationId
                    """,params(user,learnerId,scope).addValue("paymentId",paymentId).addValue("userId",user.userId()),
                    (r,n)->r.getObject("id",UUID.class));
            if(payments.size()!=1) throw missing();
        }
        var rows=jdbc.query("""
                SELECT d.id,d.document_kind,d.document_number,d.template_snapshot->>'locale' AS locale,
                  d.issued_at,d.pdf_size_bytes
                """+ELIGIBLE+(paymentId==null ? "" : " AND d.document_kind='FEE_RECEIPT' AND d.source_type='FEE_PAYMENT' AND d.source_id=:paymentId")
                        +" ORDER BY d.issued_at DESC,d.id DESC LIMIT :limit OFFSET :offset",
                params(user,learnerId,scope).addValue("paymentId",paymentId).addValue("limit",PAGE_SIZE+1).addValue("offset",page*PAGE_SIZE),
                (r,n)->new Item(r.getObject("id",UUID.class),r.getString("document_kind"),
                        r.getString("document_number"),r.getString("locale"),
                        r.getTimestamp("issued_at").toInstant(),r.getLong("pdf_size_bytes")));
        return new Page(learnerId,page,PAGE_SIZE,rows.size()>PAGE_SIZE,
                List.copyOf(rows.subList(0,Math.min(rows.size(),PAGE_SIZE))));
    }

    // Download authorization appends an audit event, so this must be a writable transaction.
    @Transactional
    public OfficialDocumentDownload download(AuthenticatedUser user, UUID learnerId, UUID documentId) {
        Scope scope=scope(user,learnerId);
        var rows=jdbc.query("SELECT d.document_kind,d.pdf_size_bytes,d.pdf_sha256,d.storage_key,d.storage_bucket "+ELIGIBLE
                +" AND d.id=:documentId",params(user,learnerId,scope).addValue("documentId",documentId),
                (r,n)->new File(r.getString("document_kind"),r.getLong("pdf_size_bytes"),
                        r.getString("pdf_sha256"),r.getString("storage_key"),r.getString("storage_bucket")));
        if(rows.isEmpty()) throw missing();
        File file=rows.getFirst();
        // Never accidentally read a same-named object after storage configuration changes.
        if(!file.bucket().equals(objectStore.bucket())) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "DOCUMENT_STORAGE_UNAVAILABLE", "The document is temporarily unavailable. Contact the school.");
        audit.recordScoped(user,scope.membershipId(),scope.schoolId(),"documents.family.download_authorized",
                "OFFICIAL_DOCUMENT",documentId.toString(),AuditOutcome.SUCCEEDED,null,
                Map.of("documentKind",file.kind()));
        // Authorization is recorded, not a claim that the browser received or opened the file.
        return new OfficialDocumentDownload("school-document-"+documentId+".pdf",file.size(),
                output->objectStore.copyVerifiedTo(file.key(),file.size(),file.sha256(),output));
    }

    private Scope scope(AuthenticatedUser user, UUID learnerId) {
        if(user==null || user.activeOrganisationId()==null || user.membershipIds().isEmpty() || learnerId==null
                || !TenantRequestContext.current().map(t->user.activeOrganisationId().equals(t.organisationId())
                    && user.userId().equals(t.userId())).orElse(false)) throw missing();
        var rows=jdbc.query("""
                SELECT m.id AS membership_id,m.school_id FROM memberships m
                JOIN user_accounts u ON u.id=m.user_id AND u.status='ACTIVE'
                JOIN organisations o ON o.id=m.organisation_id AND o.status='ACTIVE'
                JOIN schools s ON s.id=m.school_id AND s.organisation_id=m.organisation_id AND s.status='ACTIVE'
                JOIN guardians g ON g.user_account_id=m.user_id AND g.organisation_id=m.organisation_id
                  AND g.school_id=m.school_id AND g.status='ACTIVE'
                JOIN learner_guardian_relationships r ON r.guardian_id=g.id AND r.organisation_id=g.organisation_id
                  AND r.school_id=g.school_id AND r.portal_access_status='ACTIVE' AND r.learner_id=:learnerId
                JOIN learners l ON l.id=r.learner_id AND l.organisation_id=r.organisation_id
                  AND l.school_id=r.school_id AND l.status='ACTIVE'
                WHERE m.user_id=:userId AND m.organisation_id=:organisationId AND m.id IN(:membershipIds)
                  AND m.status='ACTIVE' AND m.valid_from<=:now AND (m.valid_until IS NULL OR m.valid_until>:now)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities rc ON rc.role_id=role.id WHERE mr.membership_id=m.id
                    AND (role.organisation_id IS NULL OR role.organisation_id=m.organisation_id)
                    AND rc.capability_code='portal.guardian')
                ORDER BY m.id LIMIT 1
                """,new MapSqlParameterSource("organisationId",user.activeOrganisationId())
                        .addValue("userId",user.userId()).addValue("membershipIds",user.membershipIds())
                        .addValue("learnerId",learnerId).addValue("now",Instant.now(clock)),
                (r,n)->new Scope(r.getObject("school_id",UUID.class),r.getObject("membership_id",UUID.class)));
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    private static MapSqlParameterSource params(AuthenticatedUser user, UUID learnerId, Scope scope) {
        return new MapSqlParameterSource("organisationId",user.activeOrganisationId())
                .addValue("schoolId",scope.schoolId()).addValue("learnerId",learnerId);
    }
    private static ApiException missing() {
        return new ApiException(HttpStatus.NOT_FOUND,"FAMILY_DOCUMENT_NOT_FOUND",
                "This document or learner is not available through your active family relationship.");
    }
    private record Scope(UUID schoolId,UUID membershipId) {}
    private record File(String kind,long size,String sha256,String key,String bucket) {}
    public record Item(UUID id,String documentKind,String documentNumber,String locale,Instant issuedAt,long sizeBytes) {}
    public record Page(UUID learnerId,int page,int pageSize,boolean hasMore,List<Item> documents) {}
}
