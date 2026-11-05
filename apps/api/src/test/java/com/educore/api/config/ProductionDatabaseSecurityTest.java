package com.educore.api.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductionDatabaseSecurityTest {
    @Test
    void acceptsOnlyAnAffirmativeActualDatabaseResult() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(true);
        ProductionDatabaseSecurity.verify(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);
        assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(jdbc)).isInstanceOf(IllegalStateException.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(null);
        assertThatThrownBy(() -> ProductionDatabaseSecurity.verify(jdbc)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionCannotOverrideConfigurationToRunMigrationsInTheApi() {
        var flyway = mock(Flyway.class);
        var security = new ProductionDatabaseSecurity(mock(JdbcTemplate.class));
        assertThatThrownBy(() -> security.productionApiDoesNotMigrate().migrate(flyway))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("separate DatabaseMigrationTool");
        verifyNoInteractions(flyway);
    }

    @Test
    void migrationToolRejectsMissingEnvironmentBeforeConnecting() {
        for (String omitted : new String[]{"MIGRATION_DATABASE_URL", "MIGRATION_DATABASE_USER", "MIGRATION_DATABASE_PASSWORD", "DATABASE_USER"}) {
            var environment = validEnvironment();
            environment.remove(omitted);
            assertThatThrownBy(() -> DatabaseMigrationTool.migrate(environment))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage(omitted + " is required");
        }
    }

    @Test
    void migrationToolRejectsSharedCredentialsAndNonPostgresqlTargets() {
        var environment = validEnvironment();
        environment.put("DATABASE_USER", "synthetic_migration");
        assertThatThrownBy(() -> DatabaseMigrationTool.migrate(environment)).isInstanceOf(IllegalArgumentException.class);
        environment.put("DATABASE_USER", "synthetic_runtime");
        environment.put("MIGRATION_DATABASE_URL", "jdbc:h2:mem:not-postgresql");
        assertThatThrownBy(() -> DatabaseMigrationTool.migrate(environment)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void migrationToolKeepsCredentialsOutOfLoggedJdbcUrls() {
        for (String url : new String[]{"jdbc:postgresql://localhost/synthetic?password=not-used",
                "jdbc:postgresql://localhost/synthetic?user=not-used", "jdbc:postgresql://not-used@localhost/synthetic"}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", url);
            assertThatThrownBy(() -> DatabaseMigrationTool.migrate(environment)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Map<String, String> validEnvironment() {
        return new HashMap<>(Map.of("MIGRATION_DATABASE_URL", "jdbc:postgresql://localhost/synthetic",
                "MIGRATION_DATABASE_USER", "synthetic_migration", "MIGRATION_DATABASE_PASSWORD", "not-used-to-connect",
                "DATABASE_USER", "synthetic_runtime"));
    }
}
