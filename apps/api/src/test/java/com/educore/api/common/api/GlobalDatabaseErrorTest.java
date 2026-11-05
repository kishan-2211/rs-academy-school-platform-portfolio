package com.educore.api.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalDatabaseErrorTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(Clock.systemUTC());

    @Test
    void guardRejectionsAreSafeConflictsWithoutSqlOrRowEvidence() {
        var response = handler.handleDatabase(failure("P0001"), request());
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().code()).isEqualTo("PROTECTED_RECORD_CONFLICT");
        assertThat(response.getBody().toString()).doesNotContain("synthetic-private-row", "UPDATE synthetic");
    }

    @Test
    void infrastructureFailureRemainsAnErrorWithoutSqlDetails() {
        var response = handler.handleDatabase(failure("08006"), request());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().toString()).doesNotContain("synthetic-private-row", "UPDATE synthetic");
    }

    private MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/api/v1/payroll/accounting");
    }

    private UncategorizedSQLException failure(String state) {
        return new UncategorizedSQLException("synthetic operation", "UPDATE synthetic",
                new SQLException("synthetic-private-row", state));
    }
}
