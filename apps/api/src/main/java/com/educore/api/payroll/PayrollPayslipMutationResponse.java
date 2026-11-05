package com.educore.api.payroll;

import java.util.UUID;

public record PayrollPayslipMutationResponse(UUID id, int generatedCount, boolean existing) {}
