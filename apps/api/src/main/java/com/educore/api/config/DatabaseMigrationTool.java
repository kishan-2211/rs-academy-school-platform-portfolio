package com.educore.api.config;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.core.io.ClassPathResource;

/** Separate short-lived process: no Spring application, HTTP server or school bootstrap. */
public final class DatabaseMigrationTool {
    private DatabaseMigrationTool() {}

    public static void main(String[] args) throws Exception {
        migrate(System.getenv());
    }

    static void migrate(Map<String, String> environment) throws Exception {
        String url = required(environment, "MIGRATION_DATABASE_URL");
        String user = required(environment, "MIGRATION_DATABASE_USER");
        String password = required(environment, "MIGRATION_DATABASE_PASSWORD");
        String runtimeUser = required(environment, "DATABASE_USER");
        if (!url.startsWith("jdbc:postgresql://") || user.equals(runtimeUser)
                || url.matches("(?i).*(?:[?&](?:user|password|sslpassword)=|://[^/]*@).*")) {
            throw new IllegalArgumentException("Migration requires PostgreSQL and a separate runtime login");
        }
        // Validate the operator's runtime-role choice before applying any schema changes.
        try (var connection = DriverManager.getConnection(url, user, password);
                var statement = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname=? AND rolcanlogin
                      AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication
                      AND NOT pg_has_role(rolname, current_user, 'MEMBER'))
                    """)) {
            statement.setString(1, runtimeUser);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) {
                    throw new IllegalStateException("Migration requires an existing non-privileged runtime login outside the owner role");
                }
            }
        }
        Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                .validateMigrationNaming(true).cleanDisabled(true).load().migrate();
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            try {
                try (var setting = connection.prepareStatement("SELECT set_config('educore.runtime_role', ?, true)")) {
                    setting.setString(1, runtimeUser);
                    setting.execute();
                }
                try (var statement = connection.createStatement()) {
                    statement.execute(new ClassPathResource("db/runtime-grants.sql").getContentAsString(StandardCharsets.UTF_8));
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static String required(Map<String, String> environment, String key) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
