package com.educore.api.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

class DatabaseConflictTest {
    @Test
    void recognizesGuardAndConstraintRejectionsWithoutDependingOnPrivateMessages() {
        assertThat(DatabaseConflict.isIntegrityRejection(new DataIntegrityViolationException("synthetic"))).isTrue();
        assertThat(DatabaseConflict.isIntegrityRejection(failure("P0001"))).isTrue();
        assertThat(DatabaseConflict.isIntegrityRejection(failure("23505"))).isTrue();
    }

    @Test
    void doesNotDisguiseInfrastructureOrSyntaxDefectsAsUserConflicts() {
        assertThat(DatabaseConflict.isIntegrityRejection(failure("08006"))).isFalse();
        assertThat(DatabaseConflict.isIntegrityRejection(failure("42601"))).isFalse();
        assertThat(DatabaseConflict.isIntegrityRejection(failure(null))).isFalse();
    }

    private UncategorizedSQLException failure(String state) {
        return new UncategorizedSQLException("synthetic operation", "synthetic statement",
                new SQLException("synthetic database detail", state));
    }
}
