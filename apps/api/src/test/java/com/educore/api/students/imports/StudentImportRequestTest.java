package com.educore.api.students.imports;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StudentImportRequestTest {

    @Test
    void acceptsACompleteSyntheticPreviewAndAuditedResolution() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var row = new StudentImportRowInput(
                    2,
                    "Synthetic Learner",
                    null,
                    "2015-01-15",
                    "NOT_STATED",
                    null,
                    "VI",
                    null,
                    "Synthetic Guardian",
                    "family-001",
                    "PARENT_GUARDIAN",
                    "guardian@example.test",
                    null,
                    true,
                    true);
            var preview = new PreviewStudentImportRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    LocalDate.of(2026, 4, 1),
                    "synthetic-students.csv",
                    "a".repeat(64),
                    List.of(row));
            var resolution = new ResolveStudentImportRowRequest(
                    "CREATE_NEW", "CREATE_NEW", 0L, "Verified against the synthetic migration source");

            assertThat(factory.getValidator().validate(preview)).isEmpty();
            assertThat(factory.getValidator().validate(resolution)).isEmpty();
            assertThat(factory.getValidator().validate(new CommitStudentImportRequest(0L))).isEmpty();
        }
    }

    @Test
    void rejectsOversizedUnversionedOrUnauditedRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var invalidRow = new StudentImportRowInput(
                    1,
                    "x".repeat(121),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
            var preview = new PreviewStudentImportRequest(
                    null,
                    null,
                    null,
                    "",
                    "not-a-digest",
                    List.of(invalidRow));
            var resolution = new ResolveStudentImportRowRequest("", null, -1L, "");

            assertThat(factory.getValidator().validate(preview))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains(
                            "campusId",
                            "academicSessionId",
                            "startsOn",
                            "sourceFileName",
                            "fileSha256",
                            "rows[0].sourceRowNumber",
                            "rows[0].legalName",
                            "rows[0].primaryContact",
                            "rows[0].pickupAuthorized");
            assertThat(factory.getValidator().validate(resolution))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("learnerResolution", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(new CommitStudentImportRequest(-1L)))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("expectedVersion");
        }
    }
}
