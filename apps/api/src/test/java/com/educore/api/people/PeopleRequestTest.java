package com.educore.api.people;

import static com.educore.api.people.PeopleController.*;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PeopleRequestTest {
    @Test
    void acceptsConfigurableEmploymentEvidenceAndPublicWorkflowRequests() {
        UUID school = UUID.randomUUID();
        UUID staff = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var department = new DepartmentRequest(school, "ACADEMIC", "Academic", "Teaching department",
                    "ACTIVE", null, "Synthetic configuration");
            var designation = new DesignationRequest(school, "TGT", "Trained graduate teacher",
                    "TEACHING", "TGT", true, false, false, true,
                    "Configurable designation", 10,
                    "ACTIVE", null, "Synthetic configuration");
            var profile = new StaffRequest(school, "EMP/001", "Synthetic Staff", "Synthetic Staff",
                    "EN", "staff@example.test", "+91 9999999999", null,
                    LocalDate.of(2026, 4, 1), "ACTIVE", null, "Synthetic profile");
            var assignment = new AssignmentRequest(school, staff, UUID.randomUUID(), null,
                    UUID.randomUUID(), "FULL_TIME", "SCHOOL", null, true,
                    LocalDate.of(2026, 4, 1), "ACTIVE", "Synthetic assignment");
            var qualification = new QualificationRequest(school, staff, "B.Ed", "PROFESSIONAL",
                    "Education", "Synthetic University", null, 2022, "Synthetic evidence");
            var complianceType = new ComplianceTypeRequest(school, "POLICE_VERIFICATION",
                    "Police verification", "Configurable safeguarding requirement", "TEACHING",
                    36, true, "ACTIVE", null, "Synthetic configuration");
            var compliance = new ComplianceCheckRequest(school, staff, UUID.randomUUID(),
                    "PV-SYNTHETIC-001", "Synthetic restricted note", "Synthetic evidence");
            var publicProfile = new PublicProfileRequest(school, staff, 1, null,
                    "Synthetic Staff", "Teacher", "B.Ed", "Synthetic public biography", "TGT",
                    10, null, "Synthetic public draft");
            var end = new EndRequest(school, LocalDate.of(2026, 8, 31), 0,
                    "Synthetic authority handoff");
            var decision = new DecisionRequest(school, "VERIFIED", 0,
                    "Synthetic independent review");
            var complianceDecision = new ComplianceDecisionRequest(school, "VERIFIED",
                    LocalDate.of(2026, 8, 20), LocalDate.of(2029, 8, 20), 0,
                    "Synthetic independent safeguarding review");

            assertThat(factory.getValidator().validate(department)).isEmpty();
            assertThat(factory.getValidator().validate(designation)).isEmpty();
            assertThat(factory.getValidator().validate(profile)).isEmpty();
            assertThat(factory.getValidator().validate(assignment)).isEmpty();
            assertThat(factory.getValidator().validate(qualification)).isEmpty();
            assertThat(factory.getValidator().validate(complianceType)).isEmpty();
            assertThat(factory.getValidator().validate(compliance)).isEmpty();
            assertThat(factory.getValidator().validate(publicProfile)).isEmpty();
            assertThat(factory.getValidator().validate(end)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
            assertThat(factory.getValidator().validate(complianceDecision)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeBlankEvidenceAndInvalidBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var profile = new StaffRequest(null, "", "", "", "", "not-an-email",
                    "x".repeat(31), null, null, "", -1L, null);
            var assignment = new AssignmentRequest(null, null, null, null, null, "", "",
                    "x".repeat(161), false, null, "", "");
            var qualification = new QualificationRequest(null, null, "", "", null,
                    null, null, 1949, "");
            var publicProfile = new PublicProfileRequest(null, null, 0, null, "", "",
                    null, null, null, 501, -1L, null);
            var end = new EndRequest(null, null, -1, "");

            assertThat(factory.getValidator().validate(profile))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "employeeCode", "legalName", "displayName", "primaryLanguage",
                            "workEmail", "workPhone", "joinedOn", "status", "expectedVersion");
            assertThat(factory.getValidator().validate(assignment))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "staffProfileId", "designationId", "campusId",
                            "employmentType", "employerKind", "agencyName", "startsOn", "status", "reason");
            assertThat(factory.getValidator().validate(qualification))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "staffProfileId", "name", "level", "completedYear", "reason");
            assertThat(factory.getValidator().validate(publicProfile))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "staffProfileId", "revisionNumber", "displayName",
                            "designation", "displayOrder", "expectedVersion");
            assertThat(factory.getValidator().validate(end))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "endDate", "expectedVersion", "reason");
        }
    }
}
