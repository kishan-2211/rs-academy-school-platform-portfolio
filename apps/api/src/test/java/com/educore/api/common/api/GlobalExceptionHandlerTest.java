package com.educore.api.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

class GlobalExceptionHandlerTest {
    @Test void unsupportedMethodIsPrivate405WithActualAllowedMethodsAndNoDiagnosticEcho() {
        var request = new MockHttpServletRequest("POST", "/api/v1/synthetic-read-only");
        var exception = new HttpRequestMethodNotSupportedException("SYNTHETIC_PRIVATE_METHOD", List.of("GET"));
        var response = new GlobalExceptionHandler(Clock.systemUTC()).handleUnsupportedMethod(exception, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.GET);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("private, no-store");
        assertThat(response.getHeaders().getVary()).containsExactly("Cookie");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(405);
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().detail()).isEqualTo("This action is not available for this resource.");
        assertThat(response.getBody().toString()).doesNotContain("SYNTHETIC_PRIVATE_METHOD", "HttpRequestMethodNotSupportedException");
    }

    @Test void absentAllowedMethodMetadataDoesNotInventAnotherAction() {
        var request = new MockHttpServletRequest("POST", "/api/v1/synthetic-read-only");
        var response = new GlobalExceptionHandler(Clock.systemUTC()).handleUnsupportedMethod(
                new HttpRequestMethodNotSupportedException("POST"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).isEmpty();
        assertThat(response.getBody().code()).isEqualTo("METHOD_NOT_ALLOWED");
    }
}
