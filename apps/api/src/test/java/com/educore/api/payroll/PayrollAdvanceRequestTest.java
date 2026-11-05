package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollAdvanceController.*;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

class PayrollAdvanceRequestTest {
    private static final UUID SCHOOL = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID STAFF = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID YEAR = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID RECEIVABLE = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final UUID BANK = UUID.fromString("10000000-0000-0000-0000-000000000005");

    @Test
    void acceptsScopedAdvanceAndVersionedDecisionEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            AdvanceRequest advance = request(new BigDecimal("10000.01"), 12,
                    "Synthetic staff advance", "SYNTHETIC-REFERENCE", "Synthetic preparation evidence");
            assertThat(validator.validate(advance)).isEmpty();
            assertThat(validator.validate(new UpdateAdvanceRequest(advance, 0L))).isEmpty();
            assertThat(validator.validate(new VersionedReasonRequest(SCHOOL, 1L,
                    "Synthetic submission evidence"))).isEmpty();
            assertThat(validator.validate(new DecisionRequest(SCHOOL, "APPROVE", 2L,
                    "Synthetic independent approval"))).isEmpty();
            assertThat(validator.validate(new DecisionRequest(SCHOOL, "REJECT", 2L,
                    "Synthetic independent rejection"))).isEmpty();
            assertThat(validator.validate(new DisbursementRequest(SCHOOL,
                    "Synthetic disbursement preparation"))).isEmpty();
        }
    }

    @Test
    void rejectsMissingScopeFinancialReferencesAndRequiredEvidence() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            var request = new AdvanceRequest(null, null, "", "", null, null, null,
                    null, 0, null, null, "", null, "");
            assertThat(paths(validator, request)).contains("schoolId", "staffProfileId", "advanceType",
                    "purpose", "financialYearId", "issueDate", "principalAmount", "recoveryStartMonth",
                    "installmentCount", "receivableAccountId", "disbursementAccountId", "paymentMethod", "reason");
            assertThat(paths(validator, new VersionedReasonRequest(null, null, "")))
                    .contains("schoolId", "expectedVersion", "reason");
            assertThat(paths(validator, new DecisionRequest(null, "", null, "")))
                    .contains("schoolId", "decision", "expectedVersion", "reason");
            assertThat(paths(validator, new DisbursementRequest(null, "")))
                    .contains("schoolId", "reason");
        }
    }

    @Test
    void validatesNestedEditsAndRequiresNonnegativeVersions() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            AdvanceRequest invalid = request(BigDecimal.ZERO, 121, "x", null, "x");
            assertThat(paths(validator, new UpdateAdvanceRequest(invalid, -1L)))
                    .contains("advance.principalAmount", "advance.installmentCount", "advance.purpose",
                            "advance.reason", "expectedVersion");
            assertThat(paths(validator, new UpdateAdvanceRequest(null, null)))
                    .contains("advance", "expectedVersion");
            assertThat(paths(validator, new VersionedReasonRequest(SCHOOL, -1L, "Synthetic reason")))
                    .contains("expectedVersion");
            assertThat(paths(validator, new DecisionRequest(SCHOOL, "APPROVE", -1L, "Synthetic reason")))
                    .contains("expectedVersion");
        }
    }

    @Test
    void enforcesPrincipalInstallmentAndEvidenceBoundsAtTheRequestBoundary() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            for (BigDecimal principal : List.of(new BigDecimal("-0.01"), BigDecimal.ZERO,
                    new BigDecimal("1000000000000.01"))) {
                assertThat(paths(validator, request(principal, 1, "Synthetic purpose", null,
                        "Synthetic evidence"))).contains("principalAmount");
            }
            for (int count : List.of(0, -1, 121)) {
                assertThat(paths(validator, request(new BigDecimal("100.00"), count,
                        "Synthetic purpose", null, "Synthetic evidence"))).contains("installmentCount");
            }
            assertThat(paths(validator, request(new BigDecimal("100.00"), 1,
                    "x".repeat(501), "x".repeat(121), "x".repeat(501))))
                    .contains("purpose", "paymentReference", "reason");
            assertThat(validator.validate(request(new BigDecimal("0.01"), 1,
                    "abc", null, "abc"))).isEmpty();
            assertThat(validator.validate(request(new BigDecimal("1000000000000"), 120,
                    "x".repeat(500), "x".repeat(120), "x".repeat(500)))).isEmpty();
        }
    }

    @Test
    void everyPreparationMutationRequiresPayrollAndAccountingPreparationTogether() {
        for (String name : List.of("create", "update", "submit", "cancel", "prepareDisbursement")) {
            assertCapabilityIntersection(name, "CAPABILITY_payroll.advance.prepare",
                    "CAPABILITY_accounting.prepare");
        }
    }

    @Test
    void decisionsRequireAdvanceApprovalAndAccountingPostingTogether() {
        assertCapabilityIntersection("decide", "CAPABILITY_payroll.advance.approve",
                "CAPABILITY_accounting.post");
    }

    @Test
    void advanceWorkspaceRequiresBothSensitiveReadCapabilities() {
        for (String name : List.of("workspace", "statement", "statementCsv")) {
            assertCapabilityIntersection(name, "CAPABILITY_payroll.read", "CAPABILITY_accounting.read");
        }
    }

    private static AdvanceRequest request(BigDecimal principal, int count, String purpose,
            String reference, String reason) {
        return new AdvanceRequest(SCHOOL, STAFF, "ADVANCE", purpose, YEAR, LocalDate.of(2026, 8, 20),
                principal, LocalDate.of(2026, 9, 1), count, RECEIVABLE, BANK, "BANK_TRANSFER", reference, reason);
    }

    private static List<String> paths(Validator validator, Object request) {
        return validator.validate(request).stream().map(value -> value.getPropertyPath().toString()).toList();
    }

    private static void assertCapabilityIntersection(String methodName, String first, String second) {
        List<Method> methods = Arrays.stream(PayrollAdvanceController.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName)).toList();
        assertThat(methods).as("Controller endpoint %s", methodName).hasSize(1);
        PreAuthorize authorization = methods.getFirst().getAnnotation(PreAuthorize.class);
        assertThat(authorization).as("Server authorization on %s", methodName).isNotNull();
        String expression = authorization.value();
        assertThat(authorized(expression)).as("%s without capabilities", methodName).isFalse();
        assertThat(authorized(expression, first)).as("%s with payroll only", methodName).isFalse();
        assertThat(authorized(expression, second)).as("%s with accounting only", methodName).isFalse();
        assertThat(authorized(expression, first, second)).as("%s with both capabilities", methodName).isTrue();
        assertThat(authorized(expression, "CAPABILITY_payroll.manage", "CAPABILITY_people.manage"))
                .as("%s with unrelated management capabilities", methodName).isFalse();
    }

    private static boolean authorized(String expression, String... authorities) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated("synthetic-actor", null,
                AuthorityUtils.createAuthorityList(authorities));
        SecurityExpressionRoot<?> root = new SecurityExpressionRoot<>(() -> authentication) {};
        return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(expression).getValue(root, Boolean.class));
    }
}
