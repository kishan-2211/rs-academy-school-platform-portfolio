package com.educore.api.schoolconfig;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SchoolConfigurationSnapshot(
        UUID schoolId,
        String schoolSlug,
        String legalName,
        String displayName,
        String timeZone,
        String currencyCode,
        String schoolStatus,
        String boardCode,
        String gradeRange,
        String motto,
        List<String> supportedLanguages,
        String publicEmail,
        String publicPhone,
        String principalDisplayName,
        String addressLine,
        String locality,
        String city,
        String stateName,
        String countryCode,
        Integer learnerCountMin,
        Integer learnerCountMax,
        Integer teachingStaffEstimate,
        Integer operationsStaffEstimate,
        Instant verifiedAt,
        Instant updatedAt,
        long rowVersion,
        List<CampusItem> campuses) {

    public record CampusItem(
            UUID id,
            String code,
            String displayName,
            String timeZone,
            String status,
            long rowVersion) {}
}
