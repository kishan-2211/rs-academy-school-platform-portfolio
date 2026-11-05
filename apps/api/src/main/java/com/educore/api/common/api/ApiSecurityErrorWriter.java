package com.educore.api.common.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class ApiSecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ApiSecurityErrorWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void unauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        write(
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_REQUIRED",
                "A valid session is required.");
    }

    public void forbidden(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        write(
                request,
                response,
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "You do not have permission to perform this action.");
    }

    public void mfaRequired(HttpServletRequest request, HttpServletResponse response) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "MFA_VERIFICATION_REQUIRED",
                "Set up an authenticator if needed, then sign in with a verification code before accessing privileged school information.");
    }

    private void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String detail) throws IOException {
        String requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) instanceof String value
                ? value : "unknown";
        ApiProblem problem = new ApiProblem(
                URI.create("https://docs.educore.example/problems/" + code.toLowerCase().replace('_', '-')),
                status.getReasonPhrase(),
                status.value(),
                detail,
                code,
                URI.create(request.getRequestURI()),
                requestId,
                Instant.now(clock),
                List.of(),
                Map.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
