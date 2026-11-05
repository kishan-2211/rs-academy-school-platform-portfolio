package com.educore.api.common.api;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ApiProblem(
        URI type,
        String title,
        int status,
        String detail,
        String code,
        URI instance,
        String requestId,
        Instant timestamp,
        List<FieldViolation> violations,
        Map<String, Object> details) {

    public record FieldViolation(String field, String message, String code) {
    }
}
