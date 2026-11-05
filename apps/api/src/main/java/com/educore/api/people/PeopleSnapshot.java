package com.educore.api.people;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PeopleSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canManage,
        boolean canReviewCompliance,
        boolean canPublish,
        List<CampusItem> campuses,
        List<AccountItem> accounts,
        List<DepartmentItem> departments,
        List<DesignationItem> designations,
        List<StaffItem> staff,
        List<AssignmentItem> assignments,
        List<QualificationItem> qualifications,
        List<ComplianceTypeItem> complianceTypes,
        List<ComplianceItem> complianceChecks,
        List<PublicProfileItem> publicProfiles) {

    public PeopleSnapshot {
        campuses = List.copyOf(campuses);
        accounts = List.copyOf(accounts);
        departments = List.copyOf(departments);
        designations = List.copyOf(designations);
        staff = List.copyOf(staff);
        assignments = List.copyOf(assignments);
        qualifications = List.copyOf(qualifications);
        complianceTypes = List.copyOf(complianceTypes);
        complianceChecks = List.copyOf(complianceChecks);
        publicProfiles = List.copyOf(publicProfiles);
    }

    public record CampusItem(UUID id, String name, String status) {}
    public record AccountItem(UUID userAccountId, UUID membershipId, String displayName,
            String email, List<String> roles, String membershipStatus) {
        public AccountItem { roles = List.copyOf(roles); }
    }
    public record DepartmentItem(UUID id, String code, String name, String description,
            String status, long rowVersion) {}
    public record DesignationItem(UUID id, String code, String name, String staffCategory,
            String cbseStaffCategory, boolean teachingRole, boolean leadershipRole,
            boolean eligibleAsTransportDriver, boolean eligibleAsTransportAttendant,
            String description, int displayOrder, String status, long rowVersion) {}
    public record StaffItem(UUID id, String employeeCode, String legalName, String displayName,
            String primaryLanguage, String workEmail, String workPhone, UUID userAccountId,
            LocalDate joinedOn, String status, LocalDate endedOn, String endingReason,
            long rowVersion) {}
    public record AssignmentItem(UUID id, UUID staffProfileId, UUID designationId,
            UUID departmentId, UUID campusId, String employmentType, String employerKind,
            String agencyName, boolean primaryAssignment, LocalDate startsOn, LocalDate endsOn,
            String status, String endingReason, long rowVersion) {}
    public record QualificationItem(UUID id, UUID staffProfileId, String name, String level,
            String subjectSpecialisation, String institutionName, String awardingBody,
            Integer completedYear, String verificationStatus, boolean publicSummaryEligible,
            String verificationReason, long rowVersion) {}
    public record ComplianceTypeItem(UUID id, String code, String name, String description,
            String applicableStaffCategory, Integer validityMonths,
            boolean requiredForActiveService, String status, long rowVersion) {}
    public record ComplianceItem(UUID id, UUID staffProfileId, UUID complianceTypeId,
            String status, LocalDate completedOn, LocalDate validUntil, String safeReference,
            String restrictedNote, String decisionReason, long rowVersion) {}
    public record PublicProfileItem(UUID id, UUID staffProfileId, int revisionNumber,
            UUID supersedesRevisionId, String displayName, String designation,
            String qualificationSummary, String shortBio, String cbseStaffCategory,
            int displayOrder, String status, String creatorName, String deciderName,
            String decisionReason, Instant publishedAt, long rowVersion) {}
}
