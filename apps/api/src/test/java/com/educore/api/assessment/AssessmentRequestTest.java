package com.educore.api.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import com.educore.api.assessment.AssessmentController.*;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssessmentRequestTest {

    @Test
    void acceptsConfigurableNumericAndNarrativeAssessmentRequests() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var scheme = new SchemeRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "VI",
                    "Synthetic term scheme", "WEIGHTED", "HALF_UP", 2,
                    "BOTH", new BigDecimal("33.00"), null, null);
            var marksComponent = new ComponentRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "PT1", "Periodic test",
                    "MARKS", new BigDecimal("40"), new BigDecimal("50"),
                    new BigDecimal("33"), 1, "ACTIVE", null, null);
            var narrativeComponent = new ComponentRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "OBS", "Learning observation",
                    "NARRATIVE", BigDecimal.ZERO, null, null, 2, "ACTIVE", null, null);
            var assessment = new CreateAssessmentRequest(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "Synthetic evidence", LocalDate.of(2026, 8, 21), new BigDecimal("50"));
            var marks = new RecordMarksRequest(
                    UUID.randomUUID(), 0,
                    List.of(new MarkInput(UUID.randomUUID(), "RECORDED", new BigDecimal("42"),
                            null, null, "Synthetic evidence", 0L)),
                    "Synthetic marks entry");

            assertThat(factory.getValidator().validate(scheme)).isEmpty();
            assertThat(factory.getValidator().validate(marksComponent)).isEmpty();
            assertThat(factory.getValidator().validate(narrativeComponent)).isEmpty();
            assertThat(factory.getValidator().validate(assessment)).isEmpty();
            assertThat(factory.getValidator().validate(marks)).isEmpty();
        }
    }

    @Test
    void rejectsUnsafeAssessmentBoundsAndEmptyBatches() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var band = new BandRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "", "",
                    new BigDecimal("-1"), new BigDecimal("101"), new BigDecimal("101"),
                    "", null, 1001, -1L, null);
            var component = new ComponentRequest(
                    UUID.randomUUID(), UUID.randomUUID(), "", "", "",
                    new BigDecimal("101"), new BigDecimal("10001"), new BigDecimal("101"),
                    -1, "", -1L, null);
            var marks = new RecordMarksRequest(UUID.randomUUID(), -1, List.of(), "");

            assertThat(factory.getValidator().validate(band))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("gradeCode", "name", "minimumPercentage", "maximumPercentage",
                            "gradePoint", "outcome", "displayOrder", "expectedVersion");
            assertThat(factory.getValidator().validate(component))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("code", "name", "scoringMode", "weightPercentage", "defaultMaxMarks",
                            "passPercentage", "displayOrder", "status", "expectedVersion");
            assertThat(factory.getValidator().validate(marks))
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("expectedAssessmentVersion", "marks", "reason");
        }
    }
}
