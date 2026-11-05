package com.educore.api.communication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommunicationSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canConfigure,
        boolean canSend,
        boolean canApprove,
        List<ProviderItem> providers,
        List<TemplateItem> templates,
        List<SectionOption> sections,
        List<LearnerOption> learners,
        List<AnnouncementItem> announcements) {

    public record ProviderItem(
            String channel,
            String environment,
            String providerCode,
            String senderIdentity,
            String status) {}

    public record TemplateItem(
            UUID id,
            String code,
            int revisionNumber,
            UUID supersedesTemplateId,
            String name,
            String category,
            String languageMode,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            boolean requiresAcknowledgement,
            List<String> channels,
            String status,
            long version) {}

    public record SectionOption(
            UUID id,
            String displayName,
            String gradeCode,
            String sessionName) {}

    public record LearnerOption(
            UUID id,
            String legalName,
            String admissionNumber,
            String gradeCode,
            UUID classSectionId,
            String sectionName) {}

    public record AnnouncementItem(
            UUID id,
            String category,
            String priority,
            String audienceKind,
            UUID audienceReferenceId,
            String audienceLabel,
            String languageMode,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            boolean requiresAcknowledgement,
            List<String> requestedChannels,
            Instant scheduledFor,
            Instant expiresAt,
            String status,
            String creatorName,
            String deciderName,
            String decisionReason,
            int recipientCount,
            int eligibleRecipientCount,
            int readCount,
            int acknowledgementCount,
            long version) {}
}
