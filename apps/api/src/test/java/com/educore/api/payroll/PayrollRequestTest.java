package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollController.*;
import static com.educore.api.payroll.PayrollPayslipController.*;
import static com.educore.api.payroll.PayrollRunController.*;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollRequestTest {
    @Test
    void acceptsConfigurableComponentsAndEffectiveSalaryRevisions() {
        UUID school = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var component = new ComponentRequest(school, "BASIC", "Basic salary", "EARNING",
                    "FIXED", true, true, false, "Synthetic base earning", 10,
                    "ACTIVE", null, "Synthetic component setup");
            var profile = new SalaryProfileRequest(school, UUID.randomUUID(), null, 1,
                    LocalDate.of(2026, 4, 1), "INR", "MONTHLY",
                    List.of(new SalaryLineRequest(UUID.randomUUID(),
                            new BigDecimal("20000.00"), null)), null,
                    "Synthetic initial salary profile");
            var decision = new DecisionRequest(school, "APPROVE",
                    "Synthetic independent approval", 1L);

            assertThat(factory.getValidator().validate(component)).isEmpty();
            assertThat(factory.getValidator().validate(profile)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeUnsafeValuesAndApprovalWithoutEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var component = new ComponentRequest(null, "!", "", "", "", false,
                    false, false, null, 1001, "", -1L, "");
            var profile = new SalaryProfileRequest(null, null, null, 0, null,
                    "RUPEES", "", List.of(), -1L, "");
            var line = new SalaryLineRequest(null, new BigDecimal("-1"),
                    new BigDecimal("1001"));
            var decision = new DecisionRequest(null, "", "", -1L);
            var decisionWithoutVersion = new DecisionRequest(UUID.randomUUID(), "APPROVE",
                    "Synthetic independent approval", null);
            var transitionWithoutVersion = new VersionedReasonRequest(UUID.randomUUID(), null,
                    "Synthetic submit evidence");

            assertThat(factory.getValidator().validate(component))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "code", "displayName", "componentKind",
                            "calculationMethod", "displayOrder", "status", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(profile))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "staffProfileId", "revisionNumber", "effectiveFrom",
                            "currencyCode", "payFrequency", "lines", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(line))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("payrollComponentId", "fixedAmount", "percentageRate");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "reason", "expectedVersion");
            assertThat(factory.getValidator().validate(decisionWithoutVersion))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("expectedVersion");
            assertThat(factory.getValidator().validate(transitionWithoutVersion))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("expectedVersion");
        }
    }

    @Test
    void acceptsGovernedMonthlyRunPolicyPreviewEvidenceAndDecisions() {
        UUID school = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var policy = new RunPolicyRequest(school, "Monthly attendance payroll", "INR",
                    "FULL_PAY", 6, "ACTIVE", null, "Synthetic governed policy");
            var prepare = new PrepareRunRequest(school, LocalDate.of(2026, 8, 1),
                    "a".repeat(64), "synthetic-payroll-2026-08", "Synthetic monthly preparation");
            var transition = new RunTransitionRequest(school, 0L, "Synthetic submission evidence");
            var decision = new RunDecisionRequest(school, "FINALIZE", 1L,
                    "Synthetic independent finalization");

            assertThat(factory.getValidator().validate(policy)).isEmpty();
            assertThat(factory.getValidator().validate(prepare)).isEmpty();
            assertThat(factory.getValidator().validate(transition)).isEmpty();
            assertThat(factory.getValidator().validate(decision)).isEmpty();
        }
    }

    @Test
    void rejectsUnscopedOrIncompleteMonthlyRunMutations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var policy = new RunPolicyRequest(null, "", "RUPEES", "", 1, "", -1L, "");
            var prepare = new PrepareRunRequest(null, null, "not-a-fingerprint", "short", "");
            var transition = new RunTransitionRequest(null, null, "");
            var decision = new RunDecisionRequest(null, "", null, "");

            assertThat(factory.getValidator().validate(policy))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "displayName", "currencyCode", "zeroScheduledDaysAction",
                            "prorationScale", "status", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(prepare))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "payrollMonth", "previewFingerprint", "idempotencyKey", "reason");
            assertThat(factory.getValidator().validate(transition))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "expectedVersion", "reason");
        }
    }

    @Test
    void acceptsPrivatePayslipGenerationEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var english = new GeneratePayslipsRequest(UUID.randomUUID(), "en-IN",
                    "Synthetic finalized payroll delivery");
            var hindi = new GeneratePayslipsRequest(UUID.randomUUID(), "hi-IN",
                    "Synthetic Hindi payroll delivery");

            assertThat(factory.getValidator().validate(english)).isEmpty();
            assertThat(factory.getValidator().validate(hindi)).isEmpty();
        }
    }

    @Test
    void rejectsUnscopedOrUnsupportedPrivatePayslipGeneration() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new GeneratePayslipsRequest(null, "fr-FR", "");

            assertThat(factory.getValidator().validate(request))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "locale", "reason");
        }
    }

    @Test
    void acceptsControlledPayrollRunReversalEvidenceAndIndependentDecision() {
        UUID school = UUID.randomUUID();
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new RunReversalRequest(school, 2L,
                    "Synthetic payroll correction evidence");
            var approve = new RunReversalDecisionRequest(school, "APPROVE", 0L,
                    "Synthetic independent reversal approval");
            var reject = new RunReversalDecisionRequest(school, "REJECT", 1L,
                    "Synthetic independent reversal rejection");

            assertThat(factory.getValidator().validate(request)).isEmpty();
            assertThat(factory.getValidator().validate(approve)).isEmpty();
            assertThat(factory.getValidator().validate(reject)).isEmpty();
        }
    }

    @Test
    void rejectsUnscopedIncompleteOrUnsupportedPayrollRunReversalMutations() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new RunReversalRequest(null, -1L, "");
            var decision = new RunReversalDecisionRequest(null, "", -1L, "");
            var requestWithoutVersion = new RunReversalRequest(UUID.randomUUID(), null,
                    "Synthetic payroll correction evidence");

            assertThat(factory.getValidator().validate(request))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "expectedRunVersion", "reason");
            assertThat(factory.getValidator().validate(decision))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "decision", "expectedVersion", "reason");
            assertThat(factory.getValidator().validate(requestWithoutVersion))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("expectedRunVersion");
        }
    }
}
