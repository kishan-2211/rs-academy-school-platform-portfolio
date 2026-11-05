package com.educore.api.identity;

import com.educore.api.config.CoreConfiguration;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.Console;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.CharBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

/** Deliberate, one-use operator process. Never starts Spring, an API or a worker. */
public final class FirstAdministratorTool {
    private static final UUID ADMIN_ROLE = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private FirstAdministratorTool() {}

    public static void main(String[] args) {
        char[] password = null;
        char[] confirmation = null;
        int exitCode = 0;
        try {
            Console console = System.console();
            if (args.length != 0 || console == null) {
                throw new IllegalArgumentException("A private interactive terminal without arguments is required");
            }
            Settings settings = fromEnvironment(System.getenv());
            console.printf("First administrator only: refuses every database with existing accounts.%n");
            console.printf("Confirm the approved database and scope: %s%n", settings.confirmation());
            requireConfirmation(settings, console.readLine("Retype that exact confirmation: "));
            AdminIdentity identity = identity(console.readLine("Administrator email: "),
                    console.readLine("Display name: "), console.readLine("Language (en or hi): "));
            password = console.readPassword("New password (16–200 characters; never echoed): ");
            confirmation = console.readPassword("Repeat new password: ");
            if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                throw new IllegalArgumentException("Private password confirmation failed");
            }
            provision(settings, identity, password);
            console.printf("First administrator created and audited. No session or MFA assurance was issued.%n");
            console.printf("Remove operator secrets, then use the HTTPS security centre and fresh MFA sign-in.%n");
        } catch (Exception failure) {
            // No driver/SQL/Flyway exception, credentials, input or environment values in output.
            System.err.println("First administrator setup refused or failed. No partial account was committed. Review the operator runbook privately.");
            exitCode = 1;
        } finally {
            if (password != null) Arrays.fill(password, '\0');
            if (confirmation != null) Arrays.fill(confirmation, '\0');
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    static Settings fromEnvironment(Map<String, String> environment) {
        String url = required(environment, "MIGRATION_DATABASE_URL");
        String owner = required(environment, "MIGRATION_DATABASE_USER");
        String ownerPassword = required(environment, "MIGRATION_DATABASE_PASSWORD");
        String runtime = required(environment, "DATABASE_USER");
        String database = required(environment, "FIRST_ADMIN_DATABASE_NAME");
        String reference = required(environment, "FIRST_ADMIN_APPROVAL_REFERENCE");
        if (!url.startsWith("jdbc:postgresql://") || owner.equals(runtime)
                || !owner.matches("[A-Za-z_][A-Za-z0-9_]{0,62}") || !runtime.matches("[A-Za-z_][A-Za-z0-9_]{0,62}")) {
            throw new IllegalArgumentException("Separate operator and runtime logins and a credential-free PostgreSQL URL are required");
        }
        if (!database.matches("[a-z][a-z0-9_]{2,62}") || !reference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{2,79}")) {
            throw new IllegalArgumentException("An exact database name and opaque approval reference are required");
        }
        URI target = URI.create(url.substring("jdbc:".length()));
        if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
                || !("/" + database).equals(target.getRawPath())) {
            throw new IllegalArgumentException("An exact credential-free PostgreSQL database target is required");
        }
        boolean verifiedTls = false;
        Set<String> keys = new HashSet<>();
        if (target.getRawQuery() != null) {
            for (String parameter : target.getRawQuery().split("&")) {
                String[] pair = parameter.split("=", 2);
                // PG JDBC decodes values, not names. Encoded keys must not simulate verified TLS.
                String key = pair[0];
                // Restrict to reviewed TLS configuration; URL properties otherwise override driver secrets/timeouts.
                if (!key.matches("sslmode|sslrootcert") || !keys.add(key) || pair.length != 2 || pair[1].isBlank()) {
                    throw new IllegalArgumentException("Only unique TLS parameters may be specified in the database URL");
                }
                if (key.equals("sslmode")) verifiedTls = URLDecoder.decode(pair[1], StandardCharsets.UTF_8).equals("verify-full");
            }
        }
        if (!Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(target.getHost()) && !verifiedTls) {
            throw new IllegalArgumentException("Non-loopback operator connections require certificate-verified TLS");
        }
        return new Settings(url, owner, ownerPassword, runtime, database,
                scope(environment, "FIRST_ADMIN_ORGANISATION_ID"), scope(environment, "FIRST_ADMIN_SCHOOL_ID"),
                scope(environment, "FIRST_ADMIN_CAMPUS_ID"), reference);
    }

    static AdminIdentity identity(String email, String displayName, String language) {
        var identity = new AdminIdentity(UserAccount.normalizeEmail(email), displayName == null ? "" : displayName.strip(), language);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            if (!factory.getValidator().validate(identity).isEmpty()
                    || identity.email().chars().anyMatch(Character::isISOControl)
                    || identity.displayName().chars().anyMatch(Character::isISOControl)
                    || !("en".equals(language) || "hi".equals(language))) {
                throw new IllegalArgumentException("A valid administrator identity and supported language are required");
            }
        }
        return identity;
    }

    static void validatePassword(char[] password) {
        if (password == null || password.length < 16 || password.length > 200
                || CharBuffer.wrap(password).chars().allMatch(Character::isWhitespace)
                || CharBuffer.wrap(password).chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Private password must contain 16–200 non-control characters and not be blank");
        }
    }

    static void requireConfirmation(Settings settings, String confirmation) {
        if (!settings.confirmation().equals(confirmation)) throw new IllegalArgumentException("Exact database/school confirmation is required");
    }

    static void provision(Settings settings, AdminIdentity identity, char[] password) throws Exception {
        validatePassword(password);
        identity = identity(identity.email(), identity.displayName(), identity.language());
        // The encoder accepts CharSequence: avoid converting the plaintext into an immutable String.
        String passwordHash = CoreConfiguration.newPasswordEncoder().encode(CharBuffer.wrap(password));
        Properties properties = new Properties();
        properties.setProperty("user", settings.owner);
        properties.setProperty("password", settings.ownerPassword);
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("ApplicationName", "educore-first-administrator");
        try (Connection connection = DriverManager.getConnection(settings.url, properties)) {
            // Do not inherit a role/database REPEATABLE READ default before the serialization lock.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.execute("SET LOCAL search_path = pg_catalog, public");
                jdbc.execute("SET LOCAL lock_timeout = '5s'");
                jdbc.execute("SET LOCAL statement_timeout = '20s'");
                requireOperator(connection, settings.database, settings.owner);
                Boolean runtimeSafe = jdbc.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM pg_roles runtime WHERE runtime.rolname=? AND runtime.rolcanlogin
                          AND NOT runtime.rolsuper AND NOT runtime.rolbypassrls AND NOT runtime.rolcreatedb AND NOT runtime.rolcreaterole AND NOT runtime.rolreplication
                          AND NOT pg_has_role(runtime.rolname,current_user,'MEMBER')
                          AND NOT EXISTS(SELECT 1 FROM pg_roles powerful WHERE pg_has_role(runtime.rolname,powerful.oid,'MEMBER')
                            AND (powerful.rolsuper OR powerful.rolbypassrls OR powerful.rolcreatedb OR powerful.rolcreaterole OR powerful.rolreplication
                              OR powerful.rolname IN ('pg_read_server_files','pg_write_server_files','pg_execute_server_program',
                                'pg_read_all_data','pg_write_all_data','pg_maintain')))
                          AND NOT has_database_privilege(runtime.rolname,current_database(),'CREATE')
                          AND NOT EXISTS(SELECT 1 FROM pg_namespace n WHERE n.nspname !~ '^pg_' AND n.nspname<>'information_schema'
                            AND (has_schema_privilege(runtime.rolname,n.oid,'CREATE') OR pg_has_role(runtime.rolname,n.nspowner,'MEMBER')))
                          AND NOT EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                            WHERE n.nspname='public' AND c.relkind IN ('r','p','v','m','S') AND pg_has_role(runtime.rolname,c.relowner,'MEMBER')))
                        """, Boolean.class, settings.runtime);
                if (!Boolean.TRUE.equals(runtimeSafe)) throw new IllegalStateException("Existing separate non-privileged runtime login required");
                // Blocks ordinary account INSERT as well as competing tools, not just advisory-lock users.
                jdbc.execute("LOCK TABLE public.user_accounts, public.memberships, public.membership_roles IN SHARE ROW EXCLUSIVE MODE");
                jdbc.execute("LOCK TABLE public.flyway_schema_history, public.roles, public.role_capabilities, public.capabilities IN SHARE MODE");
                String boundedUrl = settings.url + (settings.url.contains("?") ? "&" : "?") + "connectTimeout=10&socketTimeout=30";
                Flyway flyway = Flyway.configure().dataSource(boundedUrl, settings.owner, settings.ownerPassword)
                        .locations("classpath:db/migration").schemas("public").defaultSchema("public").createSchemas(false)
                        .validateMigrationNaming(true).cleanDisabled(true).ignoreMigrationPatterns(new String[0]).load();
                flyway.validate();
                var migrations = flyway.info().all();
                if (migrations.length == 0 || Arrays.stream(migrations).anyMatch(item -> item.getState() != MigrationState.SUCCESS)) {
                    throw new IllegalStateException("Database must exactly match the fully migrated release");
                }
                Boolean empty = jdbc.queryForObject("""
                        SELECT NOT EXISTS(SELECT 1 FROM public.user_accounts)
                          AND NOT EXISTS(SELECT 1 FROM public.memberships)
                          AND NOT EXISTS(SELECT 1 FROM public.audit_events WHERE action='identity.first_administrator.created')
                        """, Boolean.class);
                if (!Boolean.TRUE.equals(empty)) throw new IllegalStateException("Initial setup is forbidden after any existing identity or initial-setup evidence");
                Boolean scope = jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM public.organisations o
                          JOIN public.schools s ON s.organisation_id=o.id
                          JOIN public.campuses c ON c.organisation_id=o.id AND c.school_id=s.id
                          WHERE o.id=? AND s.id=? AND c.id=? AND o.status='ACTIVE' AND s.status='ACTIVE' AND c.status='ACTIVE'
                          FOR SHARE OF o,s,c)
                        """, Boolean.class, settings.organisation, settings.school, settings.campus);
                Boolean role = jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM public.roles WHERE id=? AND organisation_id IS NULL AND code='school_admin' AND system_role)
                          AND (SELECT count(*)=2 FROM public.role_capabilities rc JOIN public.capabilities c ON c.code=rc.capability_code
                            WHERE rc.role_id=? AND c.code IN ('identity.manage','school.configure') AND c.risk_level='RESTRICTED')
                          AND NOT EXISTS(SELECT 1 FROM public.role_capabilities WHERE role_id=?
                            AND (capability_code LIKE 'health.%' OR capability_code LIKE 'safeguarding.%'))
                        """, Boolean.class, ADMIN_ROLE, ADMIN_ROLE, ADMIN_ROLE);
                if (!Boolean.TRUE.equals(scope) || !Boolean.TRUE.equals(role)) throw new IllegalStateException("Active matching school scope and existing safe school-admin role required");
                UUID user = UUID.randomUUID(); UUID membership = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO public.user_accounts(id,login_identifier_normalized,email_normalized,display_name,preferred_language,password_hash,status)
                        VALUES(?,?,?,?,?,?,'ACTIVE')
                        """, user, identity.email(), identity.email(), identity.displayName(), identity.language(), passwordHash);
                jdbc.update("""
                        INSERT INTO public.memberships(id,user_id,organisation_id,school_id,campus_id,status,valid_from,created_at,updated_at)
                        VALUES(?,?,?,?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                        """, membership, user, settings.organisation, settings.school, settings.campus);
                jdbc.update("INSERT INTO public.membership_roles(membership_id,role_id) VALUES(?,?)", membership, ADMIN_ROLE);
                SecurityAuditService.recordFirstAdministrator(connection, settings.database, settings.owner,
                        settings.organisation, settings.school, user, membership, settings.reference, JsonMapper.builder().build(), Clock.systemUTC());
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } finally {
            properties.clear();
        }
    }

    static void requireOperator(Connection connection, String database, String owner) throws Exception {
        if (connection.getAutoCommit() || connection.isReadOnly()) throw new IllegalStateException("An explicit writable operator transaction is required");
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        Boolean safe = jdbc.queryForObject("""
                SELECT current_database()=? AND current_user=? AND current_user=session_user
                  AND EXISTS(SELECT 1 FROM pg_roles r JOIN pg_database d ON d.datdba=r.oid
                    WHERE r.rolname=current_user AND d.datname=current_database() AND r.rolcanlogin
                      AND NOT r.rolsuper AND NOT r.rolcreatedb AND NOT r.rolcreaterole AND NOT r.rolreplication)
                  AND (SELECT count(*)=7 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                    JOIN pg_roles r ON r.oid=c.relowner WHERE n.nspname='public' AND r.rolname=current_user
                    AND c.relkind='r' AND c.relname IN ('user_accounts','memberships','membership_roles','roles','role_capabilities','audit_events','flyway_schema_history'))
                  AND NOT EXISTS(SELECT 1 FROM pg_roles r WHERE r.rolname<>current_user AND pg_has_role(session_user,r.oid,'MEMBER')
                    AND (r.rolsuper OR r.rolbypassrls OR r.rolcreaterole OR r.rolcreatedb OR r.rolreplication
                      OR r.rolname IN ('pg_read_server_files','pg_write_server_files','pg_execute_server_program',
                        'pg_read_all_data','pg_write_all_data','pg_maintain')))
                """, Boolean.class, database, owner);
        if (!Boolean.TRUE.equals(safe)) throw new IllegalStateException("Exact dedicated non-superuser migration owner required; API credentials are forbidden");
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static UUID scope(Map<String, String> values, String key) {
        String value = required(values, key);
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Canonical scope IDs are required");
        return id;
    }

    record AdminIdentity(@NotBlank @Email @Size(max=254) String email,
                         @NotBlank @Size(max=120) String displayName, String language) {}

    static final class Settings {
        final String url; final String owner; final String ownerPassword; final String runtime; final String database;
        final UUID organisation; final UUID school; final UUID campus; final String reference;
        private Settings(String url, String owner, String ownerPassword, String runtime, String database,
                UUID organisation, UUID school, UUID campus, String reference) {
            this.url=url; this.owner=owner; this.ownerPassword=ownerPassword; this.runtime=runtime; this.database=database;
            this.organisation=organisation; this.school=school; this.campus=campus; this.reference=reference;
        }
        String confirmation() { return database + ":" + organisation + ":" + school + ":" + campus; }
        @Override public String toString() { return "FirstAdministratorSettings[redacted]"; }
    }
}
