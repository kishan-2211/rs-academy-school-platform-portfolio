package com.educore.api.documents;

import static com.educore.api.documents.OfficialDocumentSnapshot.DocumentItem;
import static com.educore.api.documents.OfficialDocumentSnapshot.SourceItem;
import static com.educore.api.documents.OfficialDocumentSnapshot.TemplateItem;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.documents.OfficialDocumentController.DecisionRequest;
import com.educore.api.documents.OfficialDocumentController.IssueRequest;
import com.educore.api.documents.OfficialDocumentController.TemplateRequest;
import com.educore.api.documents.OfficialDocumentController.TransitionRequest;
import com.educore.api.finance.FinanceReceiptDocumentBridge;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OfficialDocumentService {
    private static final String BONAFIDE = "BONAFIDE_CERTIFICATE";
    private static final Set<String> DOCUMENT_KINDS = Set.of("REPORT_CARD", "FEE_RECEIPT", BONAFIDE);
    private static final Set<String> LOCALES = Set.of("en-IN", "hi-IN");
    private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT");
    private static final Set<String> LABEL_KEYS = Set.of(
            "learner", "reportNumber", "classSection", "term", "session", "rollNumber",
            "results", "subject", "percentage", "grade", "outcome", "overallPercentage",
            "overallGrade", "overallOutcome", "receiptNumber", "admissionNumber", "receivedOn",
            "postingOn", "originalReceivedOn", "actualReceivedAt",
            "paymentMethod", "paymentStatus", "allocations", "feeItem", "amount", "totalReceived",
            "verification", "issuedAt", "approvedBy", "certificateNumber", "asOf", "enrolmentDetails", "enrolledSince");
    private static final String CERTIFICATE_SOURCE_FROM = """
            FROM student_enrolments enrolment
            JOIN learners learner ON learner.id=enrolment.learner_id
              AND learner.organisation_id=enrolment.organisation_id AND learner.school_id=enrolment.school_id
            JOIN academic_sessions session ON session.id=enrolment.academic_session_id
              AND session.organisation_id=enrolment.organisation_id AND session.school_id=enrolment.school_id
            JOIN campuses campus ON campus.id=enrolment.campus_id
              AND campus.organisation_id=enrolment.organisation_id AND campus.school_id=enrolment.school_id
            JOIN schools school ON school.id=enrolment.school_id AND school.organisation_id=enrolment.organisation_id
            LEFT JOIN school_profiles profile ON profile.school_id=school.id AND profile.organisation_id=school.organisation_id
            WHERE enrolment.organisation_id=:organisationId AND enrolment.school_id=:schoolId
              AND enrolment.status='ACTIVE' AND learner.status='ACTIVE' AND session.status='ACTIVE'
              AND campus.status='ACTIVE' AND school.status='ACTIVE'
              AND length(btrim(enrolment.admission_number))>0
              AND (CAST(:now AS timestamptz) AT TIME ZONE school.time_zone)::date
                BETWEEN session.starts_on AND session.ends_on
              AND enrolment.starts_on<=(CAST(:now AS timestamptz) AT TIME ZONE school.time_zone)::date
              AND (enrolment.ends_on IS NULL OR enrolment.ends_on>=
                (CAST(:now AS timestamptz) AT TIME ZONE school.time_zone)::date)
            """;
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final OfficialDocumentRenderer renderer;
    private final OfficialDocumentObjectStore objectStore;
    private final FinanceReceiptDocumentBridge receiptDocuments;
    private final String publicUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public OfficialDocumentService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            Clock clock,
            ObjectMapper objectMapper,
            OfficialDocumentRenderer renderer,
            OfficialDocumentObjectStore objectStore,
            FinanceReceiptDocumentBridge receiptDocuments,
            ApplicationProperties properties) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.renderer = renderer;
        this.objectStore = objectStore;
        this.receiptDocuments = receiptDocuments;
        this.publicUrl = stripTrailingSlash(properties.publicUrl());
    }

    @Transactional(readOnly = true)
    public OfficialDocumentSnapshot snapshot(AuthenticatedUser user, UUID schoolId) {
        Scope scope = requireScope(user, schoolId, "documents.read");
        return new OfficialDocumentSnapshot(
                scope.schoolId(), scope.schoolName(), scope.canConfigure(), scope.canIssue(),
                scope.canApprove(), scope.canRevoke(), templates(scope), sources(scope), documents(scope), scope.canReadStudents());
    }

    @Transactional(readOnly = true)
    public OfficialDocumentSnapshot.FeeReceiptPage feeReceipts(AuthenticatedUser user, UUID schoolId, UUID paymentId, int page) {
        if (page < 0 || page > 10000) throw bad("DOCUMENT_PAGE_INVALID", "Choose a valid receipt page.");
        if (schoolId == null || paymentId == null) throw bad("DOCUMENT_RECEIPT_LOOKUP_INVALID", "Choose a school and payment.");
        if (user == null || user.activeOrganisationId() == null
                || !TenantRequestContext.current().map(t -> user.userId().equals(t.userId())
                    && user.activeOrganisationId().equals(t.organisationId())).orElse(false)) {
            throw forbidden("DOCUMENT_SCOPE_DENIED", "Official document access is unavailable for this school.");
        }
        Scope scope = requireScope(user, schoolId, "documents.read", "fees.read");
        requireSourcePermission(scope, "FEE_RECEIPT");
        var payments = jdbc.query("""
                SELECT payment.receipt_number,payment.status FROM fee_payments payment
                WHERE payment.id=:paymentId AND payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                  AND current_app_user_id()=:actorUserId AND current_app_organisation_id()=:organisationId
                """, scope.params().addValue("paymentId", paymentId).addValue("actorUserId", user.userId()),
                (r, n) -> new ReceiptLookup(r.getString("receipt_number"), r.getString("status")));
        if (payments.size() != 1) throw notFound("DOCUMENT_SOURCE_NOT_FOUND", "The fee receipt source was not found.");
        var payment = payments.getFirst();
        var rows = documents(scope, paymentId, page * 25, 26);
        return new OfficialDocumentSnapshot.FeeReceiptPage(scope.schoolId(), paymentId, payment.number(), payment.status(),
                scope.canIssue() && "POSTED".equals(payment.status()), page, 25, rows.size() > 25,
                templates(scope, true), rows.subList(0, Math.min(rows.size(), 25)));
    }

    @Transactional(readOnly = true)
    public OfficialDocumentSnapshot.SourcePage certificateSources(AuthenticatedUser user, UUID schoolId, String query, int page) {
        Scope scope = requireScope(user, schoolId, "documents.read");
        requireSourcePermission(scope, BONAFIDE);
        if (page < 0 || page > 10000) throw bad("DOCUMENT_PAGE_INVALID", "Choose a valid source page.");
        String term = Objects.requireNonNullElse(clean(query, 100), "").toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<SourceItem> rows = jdbc.query("""
                SELECT enrolment.id,enrolment.admission_number,enrolment.grade_code,enrolment.created_at,
                  learner.legal_name,session.name
                """ + CERTIFICATE_SOURCE_FROM + """
                  AND (lower(learner.legal_name) LIKE :query ESCAPE '\\'
                    OR lower(enrolment.admission_number) LIKE :query ESCAPE '\\')
                ORDER BY learner.legal_name,enrolment.id LIMIT 26 OFFSET :offset
                """, scope.params().addValue("now", Instant.now(clock)).addValue("query", "%" + term + "%")
                        .addValue("offset", page * 25), (r, n) -> new SourceItem(r.getObject("id", UUID.class),
                        "STUDENT_ENROLMENT", BONAFIDE, r.getString("admission_number"), r.getString("legal_name"),
                        r.getString("grade_code") + " · " + r.getString("name"), "ACTIVE", instant(r, "created_at")));
        return new OfficialDocumentSnapshot.SourcePage(page, 25, rows.size() > 25, rows.subList(0, Math.min(25, rows.size())));
    }

    @Transactional(readOnly = true)
    public OfficialDocumentSnapshot.Review review(AuthenticatedUser user, UUID schoolId, UUID id) {
        Scope scope = requireScope(user, schoolId, "documents.read");
        var rows = jdbc.query("""
                SELECT * FROM official_documents WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", id), OfficialDocumentService::mapDocument);
        if (rows.isEmpty()) throw notFound("DOCUMENT_NOT_FOUND", "The official document was not found.");
        StoredDocument document = rows.getFirst();
        requireSourcePermission(scope, document.kind());
        return new OfficialDocumentSnapshot.Review(document.id(), document.kind(), document.status(), document.version(),
                readTree(document.sourceJson(), "Stored source evidence is invalid"),
                readTree(document.templateJson(), "Stored template evidence is invalid"));
    }

    private DocumentSource certificateSource(Scope scope, UUID id) {
        var rows = jdbc.query("""
                SELECT enrolment.id,learner.id AS learner_id,learner.legal_name,enrolment.admission_number,
                  enrolment.grade_code,enrolment.starts_on,session.name AS session_name,
                  (CAST(:now AS timestamptz) AT TIME ZONE school.time_zone)::date AS record_date,
                  school.display_name,profile.address_line,profile.locality,profile.city,profile.state_name,
                  profile.public_email,profile.public_phone
                """ + CERTIFICATE_SOURCE_FROM + """
                  AND enrolment.id=:id FOR SHARE OF enrolment,learner,session,campus,school
                """, scope.params().addValue("now", Instant.now(clock)).addValue("id", id), (r, n) -> {
            UUID learnerId = r.getObject("learner_id", UUID.class);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schemaVersion", 1);
            snapshot.put("enrolmentId", id);
            snapshot.put("school", school(r.getString("display_name"), r.getString("address_line"),
                    r.getString("locality"), r.getString("city"), r.getString("state_name"),
                    r.getString("public_email"), r.getString("public_phone")));
            snapshot.put("learner", Map.of("id", learnerId, "name", r.getString("legal_name"),
                    "admissionNumber", r.getString("admission_number")));
            snapshot.put("academic", Map.of("grade", r.getString("grade_code"), "session", r.getString("session_name")));
            snapshot.put("certificate", Map.of("asOf", r.getObject("record_date", LocalDate.class),
                    "enrolledSince", r.getObject("starts_on", LocalDate.class)));
            snapshot.put("sourceStatus", "ENROLMENT_AT_RECORD_DATE");
            return new DocumentSource(id, "STUDENT_ENROLMENT", BONAFIDE, learnerId, "", r.getString("legal_name"),
                    r.getString("grade_code") + " · " + r.getString("session_name"), snapshot);
        });
        if (rows.isEmpty()) throw notFound("DOCUMENT_SOURCE_NOT_FOUND",
                "Choose an active enrolment with an admission number in a current school session.");
        return rows.getFirst();
    }

    private DocumentSource numberCertificate(Scope scope, StoredTemplate template, DocumentSource source) {
        int year = LocalDate.parse(readTree(json(source.snapshot()), "Certificate evidence is invalid")
                .path("certificate").path("asOf").asText()).getYear();
        Long sequence = jdbc.queryForObject("""
                INSERT INTO official_certificate_counters(organisation_id,school_id,calendar_year,last_number)
                VALUES(:organisationId,:schoolId,:year,1)
                ON CONFLICT(school_id,calendar_year) DO UPDATE
                  SET last_number=official_certificate_counters.last_number+1
                RETURNING last_number
                """, scope.params().addValue("year", year), Long.class);
        String number = template.code() + "/" + year + "/" + String.format(Locale.ROOT, "%09d", sequence);
        Map<String, Object> snapshot = new LinkedHashMap<>(source.snapshot());
        snapshot.put("documentNumber", number);
        return new DocumentSource(source.id(), source.sourceType(), source.kind(), source.subjectId(), number,
                source.learnerName(), source.context(), snapshot);
    }

    private void requireUnchangedCertificate(StoredDocument document, DocumentSource source) {
        JsonNode frozen = readTree(document.sourceJson(), "Stored certificate evidence is invalid");
        JsonNode current = readTree(json(source.snapshot()), "Certificate evidence is invalid");
        boolean changed = List.of("school", "learner", "academic", "enrolmentId").stream()
                .anyMatch(field -> !frozen.path(field).equals(current.path(field)));
        if (changed || !frozen.path("certificate").path("enrolledSince")
                .equals(current.path("certificate").path("enrolledSince"))) {
            throw conflict("CERTIFICATE_SOURCE_CHANGED",
                    "The school or enrolment facts changed. Reject this request and prepare a fresh certificate.");
        }
    }

    @Transactional
    public OfficialDocumentMutationResponse createTemplate(
            AuthenticatedUser user, TemplateRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "documents.configure");
        TemplateValues values = templateValues(request);
        validateTemplateRevision(scope, values, null);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safely("DOCUMENT_TEMPLATE_EXISTS", "This template code and revision already exists.", () -> jdbc.update("""
                INSERT INTO official_document_templates(
                  id,organisation_id,school_id,template_code,revision_number,supersedes_template_id,
                  document_kind,display_name,locale,page_size,title,subtitle,footer_text,signatory_role,
                  accent_colour,approval_required,label_overrides,status,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:revision,:supersedesId,:kind,:name,:locale,
                  'A4',:title,:subtitle,:footer,:signatory,:colour,:approvalRequired,CAST(:labels AS jsonb),
                  'DRAFT',:actorMembershipId,:now,:now,0)
                """, scope.params().addValue("id", id).addValue("code", values.code())
                .addValue("revision", values.revisionNumber()).addValue("supersedesId", values.supersedesId())
                .addValue("kind", values.kind()).addValue("name", values.name()).addValue("locale", values.locale())
                .addValue("title", values.title()).addValue("subtitle", values.subtitle())
                .addValue("footer", values.footer()).addValue("signatory", values.signatory())
                .addValue("colour", values.colour()).addValue("approvalRequired", values.approvalRequired())
                .addValue("labels", json(values.labels())).addValue("now", now)));
        audit(user, scope, "documents.template.created", "OFFICIAL_DOCUMENT_TEMPLATE", id, request.reason());
        return new OfficialDocumentMutationResponse(id, 0, "DRAFT", null);
    }

    @Transactional
    public OfficialDocumentMutationResponse updateTemplate(
            AuthenticatedUser user, UUID id, TemplateRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "documents.configure");
        long version = requiredVersion(request.expectedVersion());
        TemplateValues values = templateValues(request);
        StoredTemplate current = templateForUpdate(scope, id, version);
        if (!"DRAFT".equals(current.status())) {
            throw conflict("DOCUMENT_TEMPLATE_IMMUTABLE", "Only a draft template can be edited.");
        }
        validateTemplateRevision(scope, values, id);
        int changed = safely("DOCUMENT_TEMPLATE_EXISTS", "This template code and revision already exists.",
                () -> jdbc.update("""
                UPDATE official_document_templates SET template_code=:code,revision_number=:revision,
                  supersedes_template_id=:supersedesId,document_kind=:kind,display_name=:name,
                  locale=:locale,title=:title,subtitle=:subtitle,footer_text=:footer,
                  signatory_role=:signatory,accent_colour=:colour,approval_required=:approvalRequired,
                  label_overrides=CAST(:labels AS jsonb),updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("version", version)
                .addValue("code", values.code()).addValue("revision", values.revisionNumber())
                .addValue("supersedesId", values.supersedesId()).addValue("kind", values.kind())
                .addValue("name", values.name()).addValue("locale", values.locale())
                .addValue("title", values.title()).addValue("subtitle", values.subtitle())
                .addValue("footer", values.footer()).addValue("signatory", values.signatory())
                .addValue("colour", values.colour()).addValue("approvalRequired", values.approvalRequired())
                .addValue("labels", json(values.labels())).addValue("now", Instant.now(clock))));
        requireChanged(changed, "DOCUMENT_TEMPLATE_STALE", "Refresh the template before editing it.");
        audit(user, scope, "documents.template.updated", "OFFICIAL_DOCUMENT_TEMPLATE", id, request.reason());
        return new OfficialDocumentMutationResponse(id, version + 1, "DRAFT", null);
    }

    @Transactional
    public OfficialDocumentMutationResponse publishTemplate(
            AuthenticatedUser user, UUID id, TransitionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "documents.approve");
        StoredTemplate template = templateForUpdate(scope, id, request.expectedVersion());
        if (!"DRAFT".equals(template.status())) {
            throw conflict("DOCUMENT_TEMPLATE_NOT_DRAFT", "Only a draft template can be published.");
        }
        if (!isIndependentPerson(scope, template.creatorMembershipId())) {
            throw forbidden("DOCUMENT_TEMPLATE_SELF_APPROVAL_DENIED",
                    "A different authorized person must publish this template.");
        }
        List<UUID> currentIds = jdbc.query("""
                SELECT id FROM official_document_templates
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND document_kind=:kind AND locale=:locale AND status='PUBLISHED'
                FOR UPDATE
                """, scope.params().addValue("kind", template.kind()).addValue("locale", template.locale()),
                (result, row) -> result.getObject("id", UUID.class));
        UUID currentId = currentIds.isEmpty() ? null : currentIds.getFirst();
        if (!Objects.equals(currentId, template.supersedesId())) {
            throw conflict("DOCUMENT_TEMPLATE_SUPERSESSION_REQUIRED",
                    currentId == null
                            ? "The first published template cannot supersede another template."
                            : "This revision must supersede the current template for its kind and language.");
        }
        Instant now = Instant.now(clock);
        if (currentId != null) {
            requireChanged(jdbc.update("""
                    UPDATE official_document_templates SET status='RETIRED',updated_at=:now,
                      row_version=row_version+1
                    WHERE id=:currentId AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PUBLISHED'
                    """, scope.params().addValue("currentId", currentId).addValue("now", now)),
                    "DOCUMENT_TEMPLATE_CURRENT_CHANGED", "The current template changed before publication.");
        }
        requireChanged(jdbc.update("""
                UPDATE official_document_templates SET status='PUBLISHED',
                  published_by_membership_id=:actorMembershipId,published_at=:now,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("version", request.expectedVersion())
                .addValue("now", now)), "DOCUMENT_TEMPLATE_STALE",
                "The template changed before publication.");
        audit(user, scope, "documents.template.published", "OFFICIAL_DOCUMENT_TEMPLATE", id, request.reason());
        return new OfficialDocumentMutationResponse(id, request.expectedVersion() + 1, "PUBLISHED", null);
    }

    @Transactional
    public OfficialDocumentMutationResponse requestIssue(
            AuthenticatedUser user, String idempotencyKey, IssueRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "documents.issue");
        String key = idempotency(idempotencyKey);
        String fingerprint = requestFingerprint(request);
        List<ExistingIssue> existing = jdbc.query("""
                SELECT id,row_version,status,document_number,request_fingerprint,document_kind
                FROM official_documents
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (result, row) -> new ExistingIssue(
                result.getObject("id", UUID.class), result.getLong("row_version"), result.getString("status"),
                result.getString("document_number"), result.getString("request_fingerprint"),
                result.getString("document_kind")));
        if (!existing.isEmpty()) {
            ExistingIssue issue = existing.getFirst();
            requireSourcePermission(scope, issue.kind());
            if (!issue.fingerprint().equals(fingerprint)) {
                throw conflict("DOCUMENT_IDEMPOTENCY_KEY_REUSED",
                        "This idempotency key was already used for a different document request.");
            }
            return new OfficialDocumentMutationResponse(issue.id(), issue.version(), issue.status(), issue.number());
        }
        StoredTemplate template = publishedTemplate(scope, request.templateId());
        requireSourcePermission(scope, template.kind());
        DocumentSource source = source(scope, template.kind(), request.sourceType(), request.sourceId(), true);
        StoredDocument supersedes = supersededDocument(scope, request.supersedesDocumentId(), source);
        String reason = required(request.reason(), "request reason", 240);
        if (BONAFIDE.equals(template.kind())) source = numberCertificate(scope, template, source);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        String templateJson = templateSnapshot(template);
        String sourceJson = json(source.snapshot());
        String snapshotHash = sha256(templateJson + "\n" + sourceJson);
        if (template.approvalRequired()) {
            insertIssue(scope, id, template, source, key, fingerprint, supersedes,
                    templateJson, sourceJson, snapshotHash, reason, "PENDING_APPROVAL", now, null);
            history(scope, id, "REQUESTED", null, "PENDING_APPROVAL", reason, now);
            audit(user, scope, "documents.issue.requested", "OFFICIAL_DOCUMENT", id, reason);
            return new OfficialDocumentMutationResponse(id, 0, "PENDING_APPROVAL", source.documentNumber());
        }
        IssuedPdf pdf = renderAndStore(scope, id, template, sourceJson, snapshotHash, now, user.displayName());
        insertIssue(scope, id, template, source, key, fingerprint, supersedes,
                templateJson, sourceJson, snapshotHash, reason, "ISSUED", now, pdf);
        history(scope, id, "ISSUED", null, "ISSUED", reason, now);
        completeSupersession(scope, supersedes, id, reason, now);
        audit(user, scope, "documents.issue.issued", "OFFICIAL_DOCUMENT", id, reason);
        return new OfficialDocumentMutationResponse(id, 0, "ISSUED", source.documentNumber());
    }

    @Transactional
    public OfficialDocumentMutationResponse decideIssue(
            AuthenticatedUser user, UUID id, DecisionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "documents.approve");
        String decision = enumValue(request.decision(), DECISIONS, "decision");
        String reason = required(request.reason(), "decision reason", 240);
        StoredDocument document = documentForUpdate(scope, id, request.expectedVersion());
        if (!"PENDING_APPROVAL".equals(document.status())) {
            throw conflict("DOCUMENT_NOT_PENDING", "Only a pending document can be approved or rejected.");
        }
        if (!isIndependentPerson(scope, document.requesterMembershipId())) {
            throw forbidden("DOCUMENT_SELF_APPROVAL_DENIED",
                    "A different authorized person must approve this document.");
        }
        Instant now = Instant.now(clock);
        if ("REJECT".equals(decision)) {
            requireChanged(jdbc.update("""
                    UPDATE official_documents SET status='REJECTED',
                      decided_by_membership_id=:actorMembershipId,decided_at=:now,
                      decision_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PENDING_APPROVAL' AND row_version=:version
                    """, scope.params().addValue("id", id).addValue("version", request.expectedVersion())
                    .addValue("reason", reason).addValue("now", now)), "DOCUMENT_STALE",
                    "The document changed before the decision was saved.");
            history(scope, id, "REJECTED", "PENDING_APPROVAL", "REJECTED", reason, now);
            audit(user, scope, "documents.issue.rejected", "OFFICIAL_DOCUMENT", id, reason);
            return new OfficialDocumentMutationResponse(id, request.expectedVersion() + 1,
                    "REJECTED", document.documentNumber());
        }
        DocumentSource currentSource = source(scope, document.kind(), document.sourceType(), document.sourceId(), true);
        if (BONAFIDE.equals(document.kind())) requireUnchangedCertificate(document, currentSource);
        StoredTemplate template = templateEvidence(document);
        IssuedPdf pdf = renderAndStore(scope, id, template, document.sourceJson(),
                document.snapshotHash(), now, user.displayName());
        requireChanged(jdbc.update("""
                UPDATE official_documents SET status='ISSUED',
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,decision_reason=:reason,
                  issued_by_membership_id=:actorMembershipId,issued_at=:now,
                  verification_token_sha256=:tokenHash,pdf_sha256=:pdfHash,pdf_size_bytes=:pdfSize,
                  storage_bucket=:bucket,storage_key=:storageKey,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING_APPROVAL' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("version", request.expectedVersion())
                .addValue("reason", reason).addValue("now", now).addValue("tokenHash", pdf.tokenHash())
                .addValue("pdfHash", pdf.pdfHash()).addValue("pdfSize", pdf.size())
                .addValue("bucket", pdf.bucket()).addValue("storageKey", pdf.key())), "DOCUMENT_STALE",
                "The document changed before the approval was saved.");
        history(scope, id, "APPROVED", "PENDING_APPROVAL", "ISSUED", reason, now);
        StoredDocument supersedes = supersededDocument(scope, document.supersedesId(),
                new DocumentSource(document.sourceId(), document.sourceType(), document.kind(),
                        document.subjectId(), document.documentNumber(), "", "", Map.of()));
        completeSupersession(scope, supersedes, id, reason, now);
        audit(user, scope, "documents.issue.approved", "OFFICIAL_DOCUMENT", id, reason);
        return new OfficialDocumentMutationResponse(id, request.expectedVersion() + 1,
                "ISSUED", document.documentNumber());
    }

    @Transactional
    public OfficialDocumentMutationResponse cancel(
            AuthenticatedUser user, UUID id, TransitionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "documents.revoke");
        String reason = required(request.reason(), "cancellation reason", 240);
        StoredDocument document = documentForUpdate(scope, id, request.expectedVersion());
        if (!"ISSUED".equals(document.status())) {
            throw conflict("DOCUMENT_NOT_CURRENT", "Only a current issued document can be cancelled.");
        }
        Instant now = Instant.now(clock);
        requireChanged(jdbc.update("""
                UPDATE official_documents SET status='CANCELLED',
                  cancelled_by_membership_id=:actorMembershipId,cancelled_at=:now,
                  cancellation_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='ISSUED' AND row_version=:version
                """, scope.params().addValue("id", id).addValue("version", request.expectedVersion())
                .addValue("reason", reason).addValue("now", now)), "DOCUMENT_STALE",
                "The document changed before cancellation.");
        history(scope, id, "CANCELLED", "ISSUED", "CANCELLED", reason, now);
        audit(user, scope, "documents.issue.cancelled", "OFFICIAL_DOCUMENT", id, reason);
        return new OfficialDocumentMutationResponse(id, request.expectedVersion() + 1,
                "CANCELLED", document.documentNumber());
    }

    // This read appends a scoped audit event; PostgreSQL must permit that write.
    @Transactional
    public OfficialDocumentDownload download(AuthenticatedUser user, UUID schoolId, UUID id) {
        Scope scope = requireScope(user, schoolId, "documents.read");
        List<DownloadRow> rows = jdbc.query("""
                SELECT document.document_kind,document.document_number,document.pdf_size_bytes,
                  document.pdf_sha256,document.storage_key
                FROM official_documents document
                WHERE document.id=:id AND document.organisation_id=:organisationId
                  AND document.school_id=:schoolId
                  AND document.status IN ('ISSUED','CANCELLED','SUPERSEDED')
                """, scope.params().addValue("id", id), (result, row) -> new DownloadRow(
                result.getString("document_kind"), result.getString("document_number"),
                result.getLong("pdf_size_bytes"), result.getString("pdf_sha256"), result.getString("storage_key")));
        if (rows.isEmpty()) throw notFound("DOCUMENT_NOT_FOUND", "The official document was not found.");
        DownloadRow row = rows.getFirst();
        requireSourcePermission(scope, row.kind());
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(),
                "documents.issue.downloaded", "OFFICIAL_DOCUMENT", id.toString(),
                AuditOutcome.SUCCEEDED, null, Map.of("documentKind", row.kind()));
        return new OfficialDocumentDownload(filename(row.kind(), row.number()), row.size(),
                output -> objectStore.copyVerifiedTo(row.key(), row.size(), row.sha256(), output));
    }

    @Transactional(readOnly = true)
    public PublicDocumentVerificationResponse verify(String token) {
        String normalized = token == null ? "" : token.strip();
        if (!normalized.matches("^[A-Za-z0-9_-]{43}$")) {
            throw notFound("DOCUMENT_VERIFICATION_NOT_FOUND", "No verifiable document matches this code.");
        }
        List<VerificationRow> rows = jdbc.query("""
                SELECT document.document_kind,document.document_number,document.status,
                  document.issued_at,document.pdf_sha256,school.display_name,
                  CASE
                    WHEN document.document_kind='REPORT_CARD' THEN COALESCE((
                      SELECT publication.status FROM learner_report_cards card
                      JOIN result_publications publication ON publication.id=card.result_publication_id
                      WHERE card.id=document.source_id AND card.organisation_id=document.organisation_id
                        AND card.school_id=document.school_id), 'MISSING')
                    WHEN document.document_kind='FEE_RECEIPT' THEN COALESCE((
                      SELECT payment.status FROM fee_payments payment
                      WHERE payment.id=document.source_id AND payment.organisation_id=document.organisation_id
                        AND payment.school_id=document.school_id), 'MISSING')
                    WHEN document.document_kind='BONAFIDE_CERTIFICATE' THEN CASE WHEN EXISTS(
                      SELECT 1 FROM student_enrolments enrolment
                      WHERE enrolment.id=document.source_id AND enrolment.organisation_id=document.organisation_id
                        AND enrolment.school_id=document.school_id AND enrolment.learner_id=document.subject_id
                    ) THEN 'ENROLMENT_AT_RECORD_DATE' ELSE 'MISSING' END
                    ELSE 'MISSING'
                  END AS source_status
                FROM official_documents document
                JOIN schools school ON school.id=document.school_id
                  AND school.organisation_id=document.organisation_id
                WHERE document.verification_token_sha256=:tokenHash
                """, new MapSqlParameterSource("tokenHash", sha256(normalized)),
                (result, row) -> new VerificationRow(result.getString("document_kind"),
                        result.getString("document_number"), result.getString("status"),
                        instant(result, "issued_at"), result.getString("pdf_sha256"),
                        result.getString("display_name"), result.getString("source_status")));
        if (rows.isEmpty()) throw notFound("DOCUMENT_VERIFICATION_NOT_FOUND",
                "No verifiable document matches this code.");
        VerificationRow row = rows.getFirst();
        String verificationStatus;
        if ("CANCELLED".equals(row.lifecycle())) verificationStatus = "CANCELLED";
        else if ("SUPERSEDED".equals(row.lifecycle())) verificationStatus = "SUPERSEDED";
        else if (("REPORT_CARD".equals(row.kind()) && !"PUBLISHED".equals(row.sourceStatus()))
                || ("FEE_RECEIPT".equals(row.kind()) && !"POSTED".equals(row.sourceStatus()))
                || (BONAFIDE.equals(row.kind()) && !"ENROLMENT_AT_RECORD_DATE".equals(row.sourceStatus()))) {
            verificationStatus = "SOURCE_NO_LONGER_CURRENT";
        } else verificationStatus = "VALID";
        return new PublicDocumentVerificationResponse(verificationStatus, row.schoolName(), row.kind(),
                row.number(), row.lifecycle(), row.sourceStatus(), row.issuedAt(), row.pdfHash());
    }

    private List<TemplateItem> templates(Scope scope) {
        return templates(scope, false);
    }

    private List<TemplateItem> templates(Scope scope, boolean receiptOnly) {
        return jdbc.query("""
                SELECT template.*,creator.display_name AS creator_name,
                  publisher.display_name AS publisher_name
                FROM official_document_templates template
                JOIN memberships creator_membership ON creator_membership.id=template.created_by_membership_id
                JOIN user_accounts creator ON creator.id=creator_membership.user_id
                LEFT JOIN memberships publisher_membership ON publisher_membership.id=template.published_by_membership_id
                LEFT JOIN user_accounts publisher ON publisher.id=publisher_membership.user_id
                WHERE template.organisation_id=:organisationId AND template.school_id=:schoolId
                  AND (NOT :receiptOnly OR (template.document_kind='FEE_RECEIPT' AND template.status='PUBLISHED'))
                ORDER BY template.document_kind,template.locale,template.revision_number DESC,template.id
                """, scope.params().addValue("receiptOnly", receiptOnly), (result, row) -> new TemplateItem(
                result.getObject("id", UUID.class), result.getString("template_code"),
                result.getInt("revision_number"), result.getObject("supersedes_template_id", UUID.class),
                result.getString("document_kind"), result.getString("display_name"), result.getString("locale"),
                result.getString("page_size"), result.getString("title"), result.getString("subtitle"),
                result.getString("footer_text"), result.getString("signatory_role"),
                result.getString("accent_colour"), result.getBoolean("approval_required"),
                stringMap(result.getString("label_overrides")), result.getString("status"),
                result.getString("creator_name"), result.getString("publisher_name"),
                instant(result, "published_at"), result.getLong("row_version")));
    }

    private List<SourceItem> sources(Scope scope) {
        List<SourceItem> result = new ArrayList<>();
        if (scope.canReadAssessment()) {
            result.addAll(jdbc.query("""
                    SELECT card.id,card.report_number,card.learner_name_snapshot,
                      card.section_name_snapshot,card.term_name_snapshot,publication.status,card.published_at
                    FROM learner_report_cards card
                    JOIN result_publications publication ON publication.id=card.result_publication_id
                      AND publication.organisation_id=card.organisation_id AND publication.school_id=card.school_id
                    WHERE card.organisation_id=:organisationId AND card.school_id=:schoolId
                    ORDER BY card.published_at DESC,card.id LIMIT 100
                    """, scope.params(), (r, row) -> new SourceItem(r.getObject("id", UUID.class),
                    "LEARNER_REPORT_CARD", "REPORT_CARD", r.getString("report_number"),
                    r.getString("learner_name_snapshot"),
                    r.getString("section_name_snapshot") + " · " + r.getString("term_name_snapshot"),
                    r.getString("status"), instant(r, "published_at"))));
        }
        if (scope.canReadFees()) {
            result.addAll(jdbc.query("""
                    SELECT payment.id,payment.receipt_number,learner.legal_name,session.name,
                      payment.status,payment.created_at
                    FROM fee_payments payment
                    JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
                      AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
                    JOIN learners learner ON learner.id=account.learner_id
                      AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id
                    JOIN academic_sessions session ON session.id=account.academic_session_id
                      AND session.organisation_id=account.organisation_id AND session.school_id=account.school_id
                    WHERE payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                    ORDER BY payment.created_at DESC,payment.id LIMIT 100
                    """, scope.params(), (r, row) -> new SourceItem(r.getObject("id", UUID.class),
                    "FEE_PAYMENT", "FEE_RECEIPT", r.getString("receipt_number"),
                    r.getString("legal_name"), r.getString("name"), r.getString("status"),
                    instant(r, "created_at"))));
        }
        result.sort(java.util.Comparator.comparing(SourceItem::sourceDate,
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        return List.copyOf(result);
    }

    private List<DocumentItem> documents(Scope scope) {
        return documents(scope, null, 0, 200);
    }

    private List<DocumentItem> documents(Scope scope, UUID paymentId, int offset, int limit) {
        return jdbc.query("""
                SELECT document.id,document.template_id,template.display_name,document.document_kind,
                  document.source_type,document.source_id,document.document_number,
                  document.supersedes_document_id,document.approval_required_snapshot,document.status,
                  requester.display_name AS requester_name,document.requested_at,
                  decider.display_name AS decider_name,document.decided_at,document.decision_reason,
                  issuer.display_name AS issuer_name,document.issued_at,
                  canceller.display_name AS canceller_name,document.cancelled_at,
                  document.cancellation_reason,document.superseded_at,document.pdf_sha256,document.row_version
                FROM official_documents document
                JOIN official_document_templates template ON template.id=document.template_id
                JOIN memberships requester_membership ON requester_membership.id=document.requested_by_membership_id
                JOIN user_accounts requester ON requester.id=requester_membership.user_id
                LEFT JOIN memberships decider_membership ON decider_membership.id=document.decided_by_membership_id
                LEFT JOIN user_accounts decider ON decider.id=decider_membership.user_id
                LEFT JOIN memberships issuer_membership ON issuer_membership.id=document.issued_by_membership_id
                LEFT JOIN user_accounts issuer ON issuer.id=issuer_membership.user_id
                LEFT JOIN memberships canceller_membership ON canceller_membership.id=document.cancelled_by_membership_id
                LEFT JOIN user_accounts canceller ON canceller.id=canceller_membership.user_id
                WHERE document.organisation_id=:organisationId AND document.school_id=:schoolId
                  AND (CAST(:paymentId AS uuid) IS NULL OR (document.document_kind='FEE_RECEIPT'
                    AND document.source_type='FEE_PAYMENT' AND document.source_id=:paymentId))
                  AND ((document.document_kind='REPORT_CARD' AND :canReadAssessment)
                    OR (document.document_kind='FEE_RECEIPT' AND :canReadFees)
                    OR (document.document_kind='BONAFIDE_CERTIFICATE' AND :canReadStudents))
                ORDER BY document.requested_at DESC,document.id LIMIT :limit OFFSET :offset
                """, scope.params().addValue("canReadAssessment", scope.canReadAssessment())
                .addValue("canReadFees", scope.canReadFees()).addValue("canReadStudents", scope.canReadStudents())
                .addValue("paymentId", paymentId).addValue("limit", limit).addValue("offset", offset), (r, row) -> new DocumentItem(
                r.getObject("id", UUID.class), r.getObject("template_id", UUID.class),
                r.getString("display_name"), r.getString("document_kind"), r.getString("source_type"),
                r.getObject("source_id", UUID.class), r.getString("document_number"),
                r.getObject("supersedes_document_id", UUID.class), r.getBoolean("approval_required_snapshot"),
                r.getString("status"), r.getString("requester_name"), instant(r, "requested_at"),
                r.getString("decider_name"), instant(r, "decided_at"), r.getString("decision_reason"),
                r.getString("issuer_name"), instant(r, "issued_at"), r.getString("canceller_name"),
                instant(r, "cancelled_at"), r.getString("cancellation_reason"),
                instant(r, "superseded_at"), r.getString("pdf_sha256"), r.getLong("row_version")));
    }

    private DocumentSource source(
            Scope scope, String kind, String requestedType, UUID id, boolean requireCurrent) {
        if (BONAFIDE.equals(kind)) {
            if (!"STUDENT_ENROLMENT".equals(upper(requestedType))) {
                throw bad("DOCUMENT_SOURCE_MISMATCH", "Choose a current student enrolment for this certificate.");
            }
            return certificateSource(scope, id);
        }
        if ("REPORT_CARD".equals(kind)) {
            if (!"LEARNER_REPORT_CARD".equals(upper(requestedType))) {
                throw bad("DOCUMENT_SOURCE_MISMATCH", "Choose a learner report card source for this template.");
            }
            List<ReportSourceRow> rows = jdbc.query("""
                    SELECT card.id,card.learner_id,card.report_number,card.learner_name_snapshot,
                      card.section_name_snapshot,card.term_name_snapshot,card.report_snapshot::text,
                      publication.status,school.display_name,profile.address_line,profile.locality,
                      profile.city,profile.state_name,profile.public_email,profile.public_phone
                    FROM learner_report_cards card
                    JOIN result_publications publication ON publication.id=card.result_publication_id
                      AND publication.organisation_id=card.organisation_id AND publication.school_id=card.school_id
                    JOIN schools school ON school.id=card.school_id AND school.organisation_id=card.organisation_id
                    LEFT JOIN school_profiles profile ON profile.school_id=school.id
                      AND profile.organisation_id=school.organisation_id
                    WHERE card.id=:id AND card.organisation_id=:organisationId AND card.school_id=:schoolId
                    """, scope.params().addValue("id", id), (r, row) -> new ReportSourceRow(
                    r.getObject("id", UUID.class), r.getObject("learner_id", UUID.class),
                    r.getString("report_number"), r.getString("learner_name_snapshot"),
                    r.getString("section_name_snapshot"), r.getString("term_name_snapshot"),
                    r.getString("report_snapshot"), r.getString("status"), r.getString("display_name"),
                    r.getString("address_line"), r.getString("locality"), r.getString("city"),
                    r.getString("state_name"), r.getString("public_email"), r.getString("public_phone")));
            if (rows.isEmpty()) throw notFound("DOCUMENT_SOURCE_NOT_FOUND", "The report card source was not found.");
            ReportSourceRow row = rows.getFirst();
            if (requireCurrent && !"PUBLISHED".equals(row.status())) {
                throw conflict("DOCUMENT_SOURCE_NOT_CURRENT", "Only a current published report card can be issued.");
            }
            JsonNode report = readTree(row.reportJson(), "Stored report-card evidence is invalid");
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("schemaVersion", 1);
            snapshot.put("school", school(row.schoolName(), row.address(), row.locality(), row.city(),
                    row.state(), row.email(), row.phone()));
            snapshot.put("documentNumber", row.number());
            snapshot.put("learner", report.path("learner"));
            snapshot.put("academic", report.path("academic"));
            snapshot.put("report", report);
            snapshot.put("sourceStatus", row.status());
            return new DocumentSource(row.id(), "LEARNER_REPORT_CARD", kind, row.learnerId(), row.number(),
                    row.learnerName(), row.sectionName() + " · " + row.termName(), snapshot);
        }
        if (!"FEE_PAYMENT".equals(upper(requestedType))) {
            throw bad("DOCUMENT_SOURCE_MISMATCH", "Choose a fee payment source for this template.");
        }
        List<ReceiptSourceRow> rows = jdbc.query("""
                SELECT payment.id,payment.receipt_number,payment.payment_method,
                  payment.amount,payment.status,account.currency_code,account.learner_id,
                  learner.legal_name,enrolment.admission_number,session.name AS session_name,
                  school.display_name,profile.address_line,profile.locality,profile.city,profile.state_name,
                  profile.public_email,profile.public_phone
                FROM fee_payments payment
                JOIN learner_fee_accounts account ON account.id=payment.learner_fee_account_id
                  AND account.organisation_id=payment.organisation_id AND account.school_id=payment.school_id
                JOIN learners learner ON learner.id=account.learner_id
                  AND learner.organisation_id=account.organisation_id AND learner.school_id=account.school_id
                JOIN student_enrolments enrolment ON enrolment.id=account.enrolment_id
                  AND enrolment.organisation_id=account.organisation_id AND enrolment.school_id=account.school_id
                JOIN academic_sessions session ON session.id=account.academic_session_id
                  AND session.organisation_id=account.organisation_id AND session.school_id=account.school_id
                JOIN schools school ON school.id=payment.school_id AND school.organisation_id=payment.organisation_id
                LEFT JOIN school_profiles profile ON profile.school_id=school.id
                  AND profile.organisation_id=school.organisation_id
                WHERE payment.id=:id AND payment.organisation_id=:organisationId AND payment.school_id=:schoolId
                """, scope.params().addValue("id", id), (r, row) -> new ReceiptSourceRow(
                r.getObject("id", UUID.class), r.getString("receipt_number"), r.getString("payment_method"),
                r.getBigDecimal("amount"), r.getString("status"),
                r.getString("currency_code"), r.getObject("learner_id", UUID.class),
                r.getString("legal_name"), r.getString("admission_number"), r.getString("session_name"),
                r.getString("display_name"), r.getString("address_line"), r.getString("locality"),
                r.getString("city"), r.getString("state_name"), r.getString("public_email"),
                r.getString("public_phone")));
        if (rows.isEmpty()) throw notFound("DOCUMENT_SOURCE_NOT_FOUND", "The fee receipt source was not found.");
        ReceiptSourceRow row = rows.getFirst();
        if (requireCurrent && !"POSTED".equals(row.status())) {
            throw conflict("DOCUMENT_SOURCE_NOT_CURRENT", "A reversed payment cannot receive a current receipt PDF.");
        }
        List<Map<String, Object>> allocations = jdbc.query("""
                SELECT charge.label_snapshot,allocation.amount
                FROM fee_payment_allocations allocation
                JOIN learner_fee_charges charge ON charge.id=allocation.learner_fee_charge_id
                  AND charge.organisation_id=allocation.organisation_id AND charge.school_id=allocation.school_id
                WHERE allocation.fee_payment_id=:id AND allocation.organisation_id=:organisationId
                  AND allocation.school_id=:schoolId
                ORDER BY charge.due_on,charge.id
                """, scope.params().addValue("id", id), (r, item) -> {
                    Map<String, Object> allocation = new LinkedHashMap<>();
                    allocation.put("label", r.getString("label_snapshot"));
                    allocation.put("amount", r.getBigDecimal("amount"));
                    return allocation;
                });
        Map<String, Object> payment = new LinkedHashMap<>();
        var timing = receiptDocuments.receiptTiming(scope.actorUserId(), scope.organisationId(), scope.schoolId(), row.id(),
                scope.actorMembershipId());
        payment.put("method", row.method());
        payment.put("amount", row.amount());
        payment.put("currency", row.currency());
        payment.put("postingOn", timing.postingOn());
        payment.put("originalReceivedOn", timing.originalReceivedOn());
        payment.put("actualReceivedAt", timing.actualReceivedAt());
        payment.put("receivedTimePrecision", timing.receivedTimePrecision());
        payment.put("schoolTimeZone", timing.schoolTimeZone());
        payment.put("status", row.status());
        Map<String, Object> learner = new LinkedHashMap<>();
        learner.put("id", row.learnerId());
        learner.put("name", row.learnerName());
        learner.put("admissionNumber", Objects.requireNonNullElse(row.admissionNumber(), ""));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 2);
        snapshot.put("school", school(row.schoolName(), row.address(), row.locality(), row.city(),
                row.state(), row.email(), row.phone()));
        snapshot.put("documentNumber", row.number());
        snapshot.put("learner", learner);
        snapshot.put("academic", Map.of("session", row.sessionName()));
        snapshot.put("payment", payment);
        snapshot.put("allocations", allocations);
        snapshot.put("sourceStatus", row.status());
        return new DocumentSource(row.id(), "FEE_PAYMENT", kind, row.learnerId(), row.number(),
                row.learnerName(), row.sessionName(), snapshot);
    }

    private void insertIssue(
            Scope scope, UUID id, StoredTemplate template, DocumentSource source, String key,
            String fingerprint, StoredDocument supersedes, String templateJson, String sourceJson,
            String snapshotHash, String reason, String status, Instant now, IssuedPdf pdf) {
        safely("DOCUMENT_ALREADY_ISSUED", "This source and template already has an official document.", () -> jdbc.update("""
                INSERT INTO official_documents(
                  id,organisation_id,school_id,template_id,document_kind,source_type,source_id,
                  subject_type,subject_id,document_number,idempotency_key,request_fingerprint,
                  supersedes_document_id,approval_required_snapshot,template_snapshot,source_snapshot,
                  snapshot_sha256,request_reason,status,requested_by_membership_id,requested_at,
                  issued_by_membership_id,issued_at,verification_token_sha256,pdf_sha256,pdf_size_bytes,
                  storage_bucket,storage_key,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:templateId,:kind,:sourceType,:sourceId,
                  'LEARNER',:subjectId,:number,:key,:fingerprint,:supersedesId,:approvalRequired,
                  CAST(:templateSnapshot AS jsonb),CAST(:sourceSnapshot AS jsonb),:snapshotHash,:reason,:status,
                  :actorMembershipId,:now,:issuerId,:issuedAt,:tokenHash,:pdfHash,:pdfSize,:bucket,:storageKey,
                  :now,:now,0)
                """, scope.params().addValue("id", id).addValue("templateId", template.id())
                .addValue("kind", template.kind()).addValue("sourceType", source.sourceType())
                .addValue("sourceId", source.id()).addValue("subjectId", source.subjectId())
                .addValue("number", source.documentNumber()).addValue("key", key)
                .addValue("fingerprint", fingerprint).addValue("supersedesId", supersedes == null ? null : supersedes.id())
                .addValue("approvalRequired", template.approvalRequired()).addValue("templateSnapshot", templateJson)
                .addValue("sourceSnapshot", sourceJson).addValue("snapshotHash", snapshotHash)
                .addValue("reason", reason).addValue("status", status).addValue("now", now)
                .addValue("issuerId", pdf == null ? null : scope.actorMembershipId())
                .addValue("issuedAt", pdf == null ? null : now).addValue("tokenHash", pdf == null ? null : pdf.tokenHash())
                .addValue("pdfHash", pdf == null ? null : pdf.pdfHash()).addValue("pdfSize", pdf == null ? null : pdf.size())
                .addValue("bucket", pdf == null ? null : pdf.bucket()).addValue("storageKey", pdf == null ? null : pdf.key())));
    }

    private IssuedPdf renderAndStore(
            Scope scope, UUID id, StoredTemplate template, String sourceJson,
            String snapshotHash, Instant issuedAt, String actorName) {
        String token = token();
        String tokenHash = sha256(token);
        String verificationUrl = publicUrl + "/verify/document/" + token;
        OfficialDocumentRenderer.RenderedPdf rendered = renderer.render(template.kind(),
                templateSnapshot(template), sourceJson, snapshotHash, verificationUrl, issuedAt,
                required(actorName, "approving actor", 120));
        byte[] pdfBytes = rendered.bytes();
        String key = "official-documents/" + scope.organisationId() + "/" + scope.schoolId() + "/"
                + DateTimeFormatter.ofPattern("uuuu/MM").withZone(ZoneOffset.UTC).format(issuedAt)
                + "/" + id + ".pdf";
        Path temporary = null;
        try {
            temporary = Files.createTempFile("educore-official-document-", ".pdf");
            Files.write(temporary, pdfBytes);
            objectStore.put(key, temporary, pdfBytes.length, rendered.sha256());
            registerRollbackCleanup(key);
            return new IssuedPdf(tokenHash, rendered.sha256(), pdfBytes.length,
                    objectStore.bucket(), key);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to stage the generated official document", exception);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* the operating system will clean its temporary directory */ }
            }
        }
    }

    private void registerRollbackCleanup(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return;
                try { objectStore.delete(key); }
                catch (RuntimeException ignored) { /* storage reconciliation must repair a failed rollback cleanup */ }
            }
        });
    }

    private void completeSupersession(
            Scope scope, StoredDocument supersedes, UUID replacementId, String reason, Instant now) {
        if (supersedes == null) return;
        requireChanged(jdbc.update("""
                UPDATE official_documents SET status='SUPERSEDED',superseded_at=:now,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='ISSUED' AND row_version=:version
                """, scope.params().addValue("id", supersedes.id()).addValue("version", supersedes.version())
                .addValue("now", now)), "DOCUMENT_SUPERSEDED_SOURCE_CHANGED",
                "The earlier document changed before supersession.");
        String historyReason = clean(
                "Superseded by official document " + replacementId + ": " + reason, 240);
        history(scope, supersedes.id(), "SUPERSEDED", "ISSUED", "SUPERSEDED",
                historyReason, now);
    }

    private StoredDocument supersededDocument(
            Scope scope, UUID id, DocumentSource replacementSource) {
        if (id == null) return null;
        List<StoredDocument> rows = jdbc.query("""
                SELECT * FROM official_documents
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                FOR UPDATE
                """, scope.params().addValue("id", id), OfficialDocumentService::mapDocument);
        if (rows.isEmpty()) throw notFound("DOCUMENT_SUPERSESSION_NOT_FOUND",
                "The document to supersede was not found.");
        StoredDocument document = rows.getFirst();
        if (!"ISSUED".equals(document.status()) || !document.kind().equals(replacementSource.kind())
                || !document.subjectId().equals(replacementSource.subjectId())) {
            throw conflict("DOCUMENT_SUPERSESSION_INVALID",
                    "A replacement must target a current document of the same kind and learner.");
        }
        return document;
    }

    private StoredTemplate templateForUpdate(Scope scope, UUID id, long expectedVersion) {
        List<StoredTemplate> rows = jdbc.query("""
                SELECT * FROM official_document_templates
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), this::mapTemplate);
        if (rows.isEmpty()) throw notFound("DOCUMENT_TEMPLATE_NOT_FOUND", "The document template was not found.");
        StoredTemplate template = rows.getFirst();
        if (template.version() != expectedVersion) throw conflict("DOCUMENT_TEMPLATE_STALE",
                "Refresh the template before continuing.");
        return template;
    }

    private StoredTemplate publishedTemplate(Scope scope, UUID id) {
        List<StoredTemplate> rows = jdbc.query("""
                SELECT * FROM official_document_templates
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PUBLISHED'
                """, scope.params().addValue("id", id), this::mapTemplate);
        if (rows.isEmpty()) throw notFound("DOCUMENT_TEMPLATE_NOT_PUBLISHED",
                "Choose a current published document template.");
        return rows.getFirst();
    }

    private StoredTemplate templateEvidence(StoredDocument document) {
        JsonNode node = readTree(document.templateJson(), "Stored document template evidence is invalid");
        return new StoredTemplate(document.templateId(), text(node, "code"), node.path("revisionNumber").asInt(),
                nullableUuid(node, "supersedesTemplateId"), text(node, "documentKind"), text(node, "displayName"),
                text(node, "locale"), text(node, "title"), nullableText(node, "subtitle"),
                nullableText(node, "footerText"), text(node, "signatoryRole"), text(node, "accentColour"),
                node.path("approvalRequired").asBoolean(), stringMap(node.path("labelOverrides").toString()),
                "PUBLISHED", document.requesterMembershipId(), 0);
    }

    private StoredDocument documentForUpdate(Scope scope, UUID id, long expectedVersion) {
        List<StoredDocument> rows = jdbc.query("""
                SELECT * FROM official_documents
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), OfficialDocumentService::mapDocument);
        if (rows.isEmpty()) throw notFound("DOCUMENT_NOT_FOUND", "The official document was not found.");
        StoredDocument document = rows.getFirst();
        requireSourcePermission(scope, document.kind());
        if (document.version() != expectedVersion) throw conflict("DOCUMENT_STALE",
                "Refresh the document before continuing.");
        return document;
    }

    private void validateTemplateRevision(Scope scope, TemplateValues values, UUID editingId) {
        if (values.supersedesId() == null) {
            if (values.revisionNumber() != 1) throw bad("DOCUMENT_TEMPLATE_REVISION_INVALID",
                    "A new template code starts at revision 1.");
            Integer existing = jdbc.queryForObject("""
                    SELECT count(*) FROM official_document_templates
                    WHERE organisation_id=:organisationId AND school_id=:schoolId AND template_code=:code
                      AND (CAST(:editingId AS uuid) IS NULL OR id<>:editingId)
                    """, scope.params().addValue("code", values.code()).addValue("editingId", editingId), Integer.class);
            if (existing != null && existing > 0) throw conflict("DOCUMENT_TEMPLATE_SUPERSESSION_REQUIRED",
                    "A later revision must supersede the previous template code.");
            return;
        }
        List<TemplateRevision> rows = jdbc.query("""
                SELECT id,template_code,revision_number,document_kind,locale,status
                FROM official_document_templates
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", values.supersedesId()), (r, row) -> new TemplateRevision(
                r.getObject("id", UUID.class), r.getString("template_code"), r.getInt("revision_number"),
                r.getString("document_kind"), r.getString("locale"), r.getString("status")));
        if (rows.isEmpty()) throw bad("DOCUMENT_TEMPLATE_SUPERSESSION_INVALID",
                "The superseded template was not found in this school.");
        TemplateRevision previous = rows.getFirst();
        if (!previous.code().equals(values.code()) || previous.revision() + 1 != values.revisionNumber()
                || !previous.kind().equals(values.kind()) || !previous.locale().equals(values.locale())
                || !"PUBLISHED".equals(previous.status()) || previous.id().equals(editingId)) {
            throw bad("DOCUMENT_TEMPLATE_SUPERSESSION_INVALID",
                    "A revision must follow the current published template with the same code, kind, and language.");
        }
    }

    private TemplateValues templateValues(TemplateRequest request) {
        String kind = enumValue(request.documentKind(), DOCUMENT_KINDS, "document kind");
        if (BONAFIDE.equals(kind) && !request.approvalRequired()) {
            throw bad("CERTIFICATE_APPROVAL_REQUIRED", "Enrolment certificates require independent approval.");
        }
        String locale = required(request.locale(), "template language", 20);
        if (!LOCALES.contains(locale)) throw bad("DOCUMENT_TEMPLATE_LOCALE_INVALID",
                "Choose English (India) or Hindi (India) for this renderer.");
        String colour = required(request.accentColour(), "accent colour", 7).toUpperCase(Locale.ROOT);
        if (!colour.matches("^#[0-9A-F]{6}$")) throw bad("DOCUMENT_TEMPLATE_COLOUR_INVALID",
                "Enter the accent colour as a six-digit hexadecimal value.");
        Map<String, String> labels = labels(request.labelOverrides());
        return new TemplateValues(code(request.templateCode()), request.revisionNumber(),
                request.supersedesTemplateId(), kind, required(request.displayName(), "template name", 120), locale,
                required(request.title(), "document title", 160), clean(request.subtitle(), 240),
                clean(request.footerText(), 500), required(request.signatoryRole(), "signatory role", 120),
                colour, request.approvalRequired(), labels);
    }

    private Map<String, String> labels(Map<String, String> values) {
        if (values == null || values.isEmpty()) return Map.of();
        if (values.size() > LABEL_KEYS.size()) throw bad("DOCUMENT_TEMPLATE_LABELS_INVALID",
                "Too many label overrides were supplied.");
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!LABEL_KEYS.contains(entry.getKey())) throw bad("DOCUMENT_TEMPLATE_LABELS_INVALID",
                    "The label override key is not supported: " + entry.getKey());
            result.put(entry.getKey(), required(entry.getValue(), "label override", 80));
        }
        return Map.copyOf(result);
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId, String capability) {
        return requireScope(user, requestedSchoolId, capability, null);
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId, String capability, String sourceCapability) {
        if (user == null || user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED",
                "Select a school organisation before opening official documents.");
        if (user.membershipIds() == null || user.membershipIds().isEmpty()) throw forbidden("DOCUMENT_SCOPE_DENIED",
                "Official document access is unavailable for this school.");
        List<Scope> rows = jdbc.query("""
                WITH owned_capabilities AS (
                  SELECT DISTINCT mr.membership_id,rc.capability_code
                  FROM membership_roles mr
                  JOIN memberships owner ON owner.id=mr.membership_id
                    AND owner.user_id=:userId AND owner.organisation_id=:organisationId
                  JOIN roles role ON role.id=mr.role_id
                    AND (role.organisation_id IS NULL OR role.organisation_id=owner.organisation_id)
                  JOIN role_capabilities rc ON rc.role_id=role.id
                )
                SELECT school.id AS school_id,school.display_name AS school_name,
                  membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM owned_capabilities rc
                    WHERE rc.membership_id=membership.id AND rc.capability_code='documents.configure') can_configure,
                  EXISTS(SELECT 1 FROM owned_capabilities rc
                    WHERE rc.membership_id=membership.id AND rc.capability_code='documents.issue') can_issue,
                  EXISTS(SELECT 1 FROM owned_capabilities rc
                    WHERE rc.membership_id=membership.id AND rc.capability_code='documents.approve') can_approve,
                  EXISTS(SELECT 1 FROM owned_capabilities rc
                    WHERE rc.membership_id=membership.id AND rc.capability_code='documents.revoke') can_revoke,
                  EXISTS(SELECT 1 FROM owned_capabilities rc
                    WHERE rc.membership_id=membership.id AND rc.capability_code='assessment.read') can_read_assessment,
                  EXISTS(SELECT 1 FROM owned_capabilities rc
                    WHERE rc.membership_id=membership.id AND rc.capability_code='fees.read') can_read_fees,
                  EXISTS(SELECT 1 FROM owned_capabilities rc
                    WHERE rc.membership_id=membership.id AND rc.capability_code='student.read') can_read_students
                FROM memberships membership
                JOIN user_accounts account ON account.id=membership.user_id AND account.status='ACTIVE'
                JOIN organisations organisation ON organisation.id=membership.organisation_id AND organisation.status='ACTIVE'
                JOIN schools school ON school.organisation_id=membership.organisation_id
                  AND (school.id=membership.school_id OR membership.school_id IS NULL)
                  AND school.status='ACTIVE'
                JOIN owned_capabilities role_capability ON role_capability.membership_id=membership.id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds)
                  AND (CAST(:schoolId AS uuid) IS NULL OR school.id=:schoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND role_capability.capability_code=:capability
                  AND (CAST(:sourceCapability AS text) IS NULL OR EXISTS (
                    SELECT 1 FROM owned_capabilities source_grant
                    WHERE source_grant.membership_id=membership.id AND source_grant.capability_code=:sourceCapability))
                ORDER BY school.display_name,membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", requestedSchoolId)
                .addValue("capability", capability).addValue("sourceCapability", sourceCapability), (r, row) -> new Scope(user.userId(), user.activeOrganisationId(),
                r.getObject("school_id", UUID.class), r.getString("school_name"),
                r.getObject("membership_id", UUID.class), r.getBoolean("can_configure"),
                r.getBoolean("can_issue"), r.getBoolean("can_approve"), r.getBoolean("can_revoke"),
                r.getBoolean("can_read_assessment"), r.getBoolean("can_read_fees"), r.getBoolean("can_read_students")));
        if (rows.isEmpty()) throw forbidden("DOCUMENT_SCOPE_DENIED",
                "Official document access is unavailable for this school.");
        return rows.getFirst();
    }

    private boolean isIndependentPerson(Scope scope, UUID makerMembershipId) {
        // A person may have school-wide and campus memberships. Historical makers
        // remain the same person even after their original membership has ended.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT official_document_actor_is_independent(
                  :actorMembershipId,:makerMembershipId,:organisationId,:schoolId)
                """, scope.params().addValue("makerMembershipId", makerMembershipId), Boolean.class));
    }

    private void requireSourcePermission(Scope scope, String kind) {
        if (("REPORT_CARD".equals(kind) && !scope.canReadAssessment())
                || ("FEE_RECEIPT".equals(kind) && !scope.canReadFees())
                || (BONAFIDE.equals(kind) && !scope.canReadStudents()) || !DOCUMENT_KINDS.contains(kind)) {
            throw forbidden("DOCUMENT_SOURCE_SCOPE_DENIED",
                    "The source record is outside this membership's authorized school work.");
        }
    }

    private String templateSnapshot(StoredTemplate template) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 1);
        value.put("id", template.id());
        value.put("code", template.code());
        value.put("revisionNumber", template.revision());
        value.put("supersedesTemplateId", template.supersedesId());
        value.put("documentKind", template.kind());
        value.put("displayName", template.name());
        value.put("locale", template.locale());
        value.put("pageSize", "A4");
        value.put("title", template.title());
        value.put("subtitle", template.subtitle());
        value.put("footerText", template.footer());
        value.put("signatoryRole", template.signatory());
        value.put("accentColour", template.colour());
        value.put("approvalRequired", template.approvalRequired());
        value.put("labelOverrides", template.labels());
        return json(value);
    }

    private void history(
            Scope scope, UUID documentId, String event, String previous, String next,
            String reason, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO official_document_history(
                  id,organisation_id,school_id,official_document_id,event_type,
                  previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:documentId,:event,:previous,:next,
                  :reason,:actorMembershipId,:occurredAt)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("documentId", documentId)
                .addValue("event", event).addValue("previous", previous).addValue("next", next)
                .addValue("reason", required(reason, "history reason", 240)).addValue("occurredAt", occurredAt));
    }

    private void audit(
            AuthenticatedUser user, Scope scope, String action, String type, UUID id, String reason) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, clean(reason, 240), Map.of());
    }

    private String token() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, Object> school(
            String name, String address, String locality, String city, String state,
            String email, String phone) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("address", join(address, locality, city, state));
        result.put("contact", join(phone, email));
        return result;
    }

    private static String join(String... values) {
        return java.util.Arrays.stream(values).filter(Objects::nonNull).map(String::strip)
                .filter(value -> !value.isBlank()).distinct().collect(java.util.stream.Collectors.joining(", "));
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Unable to encode document evidence", exception); }
    }

    private JsonNode readTree(String value, String message) {
        try { return objectMapper.readTree(value); }
        catch (JacksonException exception) { throw new IllegalStateException(message, exception); }
    }

    private Map<String, String> stringMap(String value) {
        try { return objectMapper.readValue(value, STRING_MAP); }
        catch (JacksonException exception) { throw new IllegalStateException("Stored template labels are invalid", exception); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String filename(String kind, String number) {
        String stem = number.replaceAll("[^A-Za-z0-9._-]", "-").replaceAll("-+", "-");
        return kind.toLowerCase(Locale.ROOT).replace('_', '-') + "-" + stem + ".pdf";
    }

    private static String idempotency(String value) {
        String result = required(value, "Idempotency-Key header", 100);
        if (!result.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{7,99}$")) throw bad("DOCUMENT_IDEMPOTENCY_KEY_INVALID",
                "Use an opaque idempotency key between 8 and 100 supported characters.");
        return result;
    }

    private static String requestFingerprint(IssueRequest request) {
        if (request.templateId() == null || request.sourceId() == null) {
            throw bad("DOCUMENT_ISSUE_REFERENCE_REQUIRED", "Choose a template and source record.");
        }
        return sha256(request.templateId() + "|" + upper(request.sourceType()) + "|" + request.sourceId()
                + "|" + Objects.toString(request.supersedesDocumentId(), ""));
    }

    private static String code(String value) {
        String result = upper(value);
        if (!result.matches("^[A-Z][A-Z0-9_]{1,39}$")) throw bad("DOCUMENT_TEMPLATE_CODE_INVALID",
                "Use 2–40 uppercase letters, numbers, or underscores, starting with a letter.");
        return result;
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String result = upper(value);
        if (!allowed.contains(result)) throw bad("DOCUMENT_VALUE_INVALID", "Choose a supported " + label + ".");
        return result;
    }

    private static String upper(String value) {
        return required(value, "value", 100).toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String required(String value, String label, int maximum) {
        String result = clean(value, maximum);
        if (result == null) throw bad("DOCUMENT_REQUIRED_VALUE", "Enter the " + label + ".");
        return result;
    }

    private static String clean(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String result = value.replaceAll("[\\p{Cc}]", " ").replaceAll("\\s+", " ").strip();
        if (result.length() > maximum) throw bad("DOCUMENT_VALUE_TOO_LONG",
                "A document value exceeds the supported length.");
        return result;
    }

    private static long requiredVersion(Long value) {
        if (value == null) throw bad("EXPECTED_VERSION_REQUIRED",
                "Refresh the template and include its current version.");
        return value;
    }

    private static String stripTrailingSlash(String value) {
        String result = required(value, "public application URL", 500);
        return result.endsWith("/") ? result.substring(0, result.length() - 1) : result;
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column) == null ? null : result.getTimestamp(column).toInstant();
    }

    private StoredTemplate mapTemplate(ResultSet r, int row) throws SQLException {
        return new StoredTemplate(r.getObject("id", UUID.class), r.getString("template_code"),
                r.getInt("revision_number"), r.getObject("supersedes_template_id", UUID.class),
                r.getString("document_kind"), r.getString("display_name"), r.getString("locale"),
                r.getString("title"), r.getString("subtitle"), r.getString("footer_text"),
                r.getString("signatory_role"), r.getString("accent_colour"), r.getBoolean("approval_required"),
                stringMap(r.getString("label_overrides")), r.getString("status"),
                r.getObject("created_by_membership_id", UUID.class), r.getLong("row_version"));
    }

    private static StoredDocument mapDocument(ResultSet r, int row) throws SQLException {
        return new StoredDocument(r.getObject("id", UUID.class), r.getObject("template_id", UUID.class),
                r.getString("document_kind"), r.getString("source_type"), r.getObject("source_id", UUID.class),
                r.getObject("subject_id", UUID.class), r.getString("document_number"),
                r.getObject("supersedes_document_id", UUID.class), r.getString("template_snapshot"),
                r.getString("source_snapshot"), r.getString("snapshot_sha256"), r.getString("status"),
                r.getObject("requested_by_membership_id", UUID.class), r.getLong("row_version"));
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        if (value.isBlank()) throw new IllegalStateException("Stored template field is missing: " + field);
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        return value.isBlank() ? null : value;
    }

    private static UUID nullableUuid(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        return value.isBlank() ? null : UUID.fromString(value);
    }

    private static void requireChanged(int changed, String code, String message) {
        if (changed != 1) throw conflict(code, message);
    }

    private static <T> T safely(String code, String message, Work<T> work) {
        try { return work.run(); }
        catch (DataIntegrityViolationException exception) { throw conflict(code, message); }
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Scope(
            UUID actorUserId, UUID organisationId, UUID schoolId, String schoolName, UUID actorMembershipId,
            boolean canConfigure, boolean canIssue, boolean canApprove, boolean canRevoke,
            boolean canReadAssessment, boolean canReadFees, boolean canReadStudents) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record TemplateValues(
            String code, int revisionNumber, UUID supersedesId, String kind, String name,
            String locale, String title, String subtitle, String footer, String signatory,
            String colour, boolean approvalRequired, Map<String, String> labels) {}

    private record StoredTemplate(
            UUID id, String code, int revision, UUID supersedesId, String kind, String name,
            String locale, String title, String subtitle, String footer, String signatory,
            String colour, boolean approvalRequired, Map<String, String> labels, String status,
            UUID creatorMembershipId, long version) {}

    private record StoredDocument(
            UUID id, UUID templateId, String kind, String sourceType, UUID sourceId, UUID subjectId,
            String documentNumber, UUID supersedesId, String templateJson, String sourceJson,
            String snapshotHash, String status, UUID requesterMembershipId, long version) {}

    private record DocumentSource(
            UUID id, String sourceType, String kind, UUID subjectId, String documentNumber,
            String learnerName, String context, Map<String, Object> snapshot) {}

    private record TemplateRevision(
            UUID id, String code, int revision, String kind, String locale, String status) {}

    private record ExistingIssue(
            UUID id, long version, String status, String number, String fingerprint, String kind) {}
    private record IssuedPdf(String tokenHash, String pdfHash, long size, String bucket, String key) {}
    private record DownloadRow(String kind, String number, long size, String sha256, String key) {}
    private record VerificationRow(
            String kind, String number, String lifecycle, Instant issuedAt,
            String pdfHash, String schoolName, String sourceStatus) {}

    private record ReportSourceRow(
            UUID id, UUID learnerId, String number, String learnerName, String sectionName,
            String termName, String reportJson, String status, String schoolName,
            String address, String locality, String city, String state, String email, String phone) {}

    private record ReceiptLookup(String number, String status) {}

    private record ReceiptSourceRow(
            UUID id, String number, String method, BigDecimal amount,
            String status, String currency, UUID learnerId, String learnerName,
            String admissionNumber, String sessionName, String schoolName, String address,
            String locality, String city, String state, String email, String phone) {}

    @FunctionalInterface
    private interface Work<T> { T run(); }
}
