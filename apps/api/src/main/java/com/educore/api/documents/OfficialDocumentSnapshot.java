package com.educore.api.documents;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record OfficialDocumentSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canConfigure,
        boolean canIssue,
        boolean canApprove,
        boolean canRevoke,
        List<TemplateItem> templates,
        List<SourceItem> sources,
        List<DocumentItem> documents,
        boolean canReadStudents) {

    public OfficialDocumentSnapshot {
        templates = List.copyOf(templates);
        sources = List.copyOf(sources);
        documents = List.copyOf(documents);
    }

    public record SourcePage(int page, int pageSize, boolean hasMore, List<SourceItem> sources) {
        public SourcePage { sources = List.copyOf(sources); }
    }

    /** Exact payment history; it is independent of the operating snapshot's recent-source limits. */
    public record FeeReceiptPage(UUID schoolId, UUID paymentId, String receiptNumber, String sourceStatus,
                                 boolean canIssue, int page, int pageSize, boolean hasMore,
                                 List<TemplateItem> templates, List<DocumentItem> documents) {
        public FeeReceiptPage {
            templates = List.copyOf(templates);
            documents = List.copyOf(documents);
        }
    }

    public record Review(UUID documentId, String documentKind, String status, long rowVersion,
                         JsonNode sourceSnapshot, JsonNode templateSnapshot) {}

    public record TemplateItem(
            UUID id,
            String code,
            int revisionNumber,
            UUID supersedesTemplateId,
            String documentKind,
            String displayName,
            String locale,
            String pageSize,
            String title,
            String subtitle,
            String footerText,
            String signatoryRole,
            String accentColour,
            boolean approvalRequired,
            Map<String, String> labelOverrides,
            String status,
            String creatorName,
            String publisherName,
            Instant publishedAt,
            long rowVersion) {
        public TemplateItem {
            labelOverrides = Map.copyOf(labelOverrides);
        }
    }

    public record SourceItem(
            UUID id,
            String sourceType,
            String documentKind,
            String reference,
            String learnerName,
            String contextLabel,
            String sourceStatus,
            Instant sourceDate) {}

    public record DocumentItem(
            UUID id,
            UUID templateId,
            String templateName,
            String documentKind,
            String sourceType,
            UUID sourceId,
            String documentNumber,
            UUID supersedesDocumentId,
            boolean approvalRequired,
            String status,
            String requestedBy,
            Instant requestedAt,
            String decidedBy,
            Instant decidedAt,
            String decisionReason,
            String issuedBy,
            Instant issuedAt,
            String cancelledBy,
            Instant cancelledAt,
            String cancellationReason,
            Instant supersededAt,
            String pdfSha256,
            long rowVersion) {}
}
