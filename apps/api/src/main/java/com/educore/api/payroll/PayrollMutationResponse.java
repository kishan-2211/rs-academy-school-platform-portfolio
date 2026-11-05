package com.educore.api.payroll;

import java.util.UUID;

public record PayrollMutationResponse(UUID id, long rowVersion) {}
