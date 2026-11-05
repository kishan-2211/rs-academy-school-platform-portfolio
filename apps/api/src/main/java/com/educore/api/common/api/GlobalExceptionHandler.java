package com.educore.api.common.api;

import com.educore.api.files.FileInfrastructureException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiProblem> handleApiException(ApiException exception, HttpServletRequest request) {
        return problem(
                exception.status(),
                exception.code(),
                exception.getMessage(),
                List.of(),
                exception.details(),
                request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiProblem> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        // MVC method-security denials must not be swallowed by the generic 500 handler.
        // Do not expose the denied capability, actor or exception diagnostics.
        return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this action.", List.of(), Map.of(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiProblem> handleMissingRequestParameter(
            MissingServletRequestParameterException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more values are invalid.",
                List.of(), Map.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiProblem> handleUnsupportedMethod(
            HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        // A rejected HTTP method is not a server failure. Never echo the attempted
        // method, request body or exception diagnostics, or log them as an outage.
        var response = problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "This action is not available for this resource.", List.of(), Map.of(), request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).headers(headers -> {
            var supported = exception.getSupportedHttpMethods();
            if (supported != null && !supported.isEmpty()) headers.setAllow(supported);
            headers.setCacheControl("private, no-store");
            headers.setVary(List.of("Cookie"));
            headers.set("X-Content-Type-Options", "nosniff");
        }).body(response.getBody());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiProblem> handleRequestTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more values are invalid.",
                List.of(), Map.of(), request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiProblem> handleMethodValidation(
            HandlerMethodValidationException exception, HttpServletRequest request) {
        // Do not echo rejected query values or actor details. Invalid server output is not a client error.
        if (exception.isForReturnValue()) return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The request could not be completed.", List.of(), Map.of(), request);
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more values are invalid.",
                List.of(), Map.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblem> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ApiProblem.FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> new ApiProblem.FieldViolation(
                        error.getField(),
                        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage(),
                        "INVALID_FIELD"))
                .toList();
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more fields are invalid.",
                violations,
                Map.of(),
                request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiProblem> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        List<ApiProblem.FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ApiProblem.FieldViolation(
                        violation.getPropertyPath().toString(), violation.getMessage(), "INVALID_VALUE"))
                .toList();
        return problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more values are invalid.",
                violations,
                Map.of(),
                request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiProblem> handleUnreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "The request body could not be read.",
                List.of(),
                Map.of(),
                request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiProblem> handleOversizedUpload(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "FILE_TOO_LARGE",
                "The file exceeds the configured private upload limit.",
                List.of(),
                Map.of(),
                request);
    }

    @ExceptionHandler(FileInfrastructureException.class)
    ResponseEntity<ApiProblem> handleFileInfrastructure(
            FileInfrastructureException exception,
            HttpServletRequest request) {
        String requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) instanceof String value
                ? value : "unknown";
        log.error("Private file infrastructure error requestId={}", requestId, exception);
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "FILE_SERVICE_UNAVAILABLE",
                "Private file processing is temporarily unavailable. Try again later.",
                List.of(),
                Map.of(),
                request);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ApiProblem> handleDatabase(DataAccessException exception, HttpServletRequest request) {
        if (DatabaseConflict.isIntegrityRejection(exception)) {
            return problem(HttpStatus.CONFLICT, "PROTECTED_RECORD_CONFLICT",
                    "The change conflicts with protected records. Refresh and review the required approvals.",
                    List.of(), Map.of(), request);
        }
        String requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) instanceof String value
                ? value : "unknown";
        // JDBC exceptions can include SQL and rejected row contents; never log those to application logs.
        log.error("Database operation failed requestId={} category={}", requestId, exception.getClass().getSimpleName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The request could not be completed.", List.of(), Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblem> handleUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) instanceof String value
                ? value : "unknown";
        log.error("Unhandled API error requestId={}", requestId, exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The request could not be completed.",
                List.of(),
                Map.of(),
                request);
    }

    private ResponseEntity<ApiProblem> problem(
            HttpStatus status,
            String code,
            String detail,
            List<ApiProblem.FieldViolation> violations,
            Map<String, Object> details,
            HttpServletRequest request) {
        String requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE) instanceof String value
                ? value : "unknown";
        URI instance = URI.create(request.getRequestURI());
        ApiProblem body = new ApiProblem(
                URI.create("https://docs.educore.example/problems/" + code.toLowerCase().replace('_', '-')),
                status.getReasonPhrase(),
                status.value(),
                detail,
                code,
                instance,
                requestId,
                Instant.now(clock),
                violations,
                details);
        return ResponseEntity.status(status).body(body);
    }
}
