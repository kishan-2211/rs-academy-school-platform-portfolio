package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure input guards only: never invokes main/provision, connects, or creates an account. */
class FirstAdministratorToolTest {
    private static final String DATABASE = "educore_first_admin_unit";
    private static final String ORGANISATION = UUID.randomUUID().toString();
    private static final String SCHOOL = UUID.randomUUID().toString();
    private static final String CAMPUS = UUID.randomUUID().toString();
    private static final String[] REQUIRED_SETTINGS = {
        "MIGRATION_DATABASE_URL", "MIGRATION_DATABASE_USER", "MIGRATION_DATABASE_PASSWORD", "DATABASE_USER",
        "FIRST_ADMIN_DATABASE_NAME", "FIRST_ADMIN_ORGANISATION_ID", "FIRST_ADMIN_SCHOOL_ID",
        "FIRST_ADMIN_CAMPUS_ID", "FIRST_ADMIN_APPROVAL_REFERENCE"
    };

    @Test
    void acceptsExplicitConfigurationWithoutContactingItsDatabase() {
        var settings = FirstAdministratorTool.fromEnvironment(validEnvironment());
        assertThat(settings.confirmation()).isEqualTo(DATABASE + ":" + ORGANISATION + ":" + SCHOOL + ":" + CAMPUS);
    }

    @Test
    void rejectsEveryMissingOrBlankRequiredSetting() {
        for (String key : REQUIRED_SETTINGS) {
            var omitted = validEnvironment();
            omitted.remove(key);
            assertInvalidEnvironment(omitted);
            for (String blank : new String[]{"", " ", "\t"}) {
                var empty = validEnvironment();
                empty.put(key, blank);
                assertInvalidEnvironment(empty);
            }
        }
    }

    @Test
    void rejectsNonPostgresqlAndMalformedDatabaseTargets() {
        for (String url : new String[]{
                "jdbc:h2:mem:" + DATABASE,
                "https://example.test/" + DATABASE,
                "jdbc:postgresql://",
                "jdbc:postgresql://127.0.0.1:5432/",
                "jdbc:postgresql://127.0.0.1:5432/different_database",
                "jdbc:postgresql://127.0.0.1:5432/" + DATABASE + "\n"}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", url);
            assertInvalidEnvironment(environment);
        }
    }

    @Test
    void refusesCredentialsEmbeddedInJdbcUrls() {
        String base = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
        for (String url : new String[]{
                base + "?user=synthetic",
                base + "?password=synthetic",
                base + "?sslpassword=synthetic",
                base + "?sslmode=verify-full&password=synthetic",
                base + "?USER=synthetic",
                base + "?SSLPASSWORD=synthetic",
                base + "?%75ser=synthetic",
                base + "?%70assword=synthetic",
                base + "?ssl%70assword=synthetic",
                "jdbc:postgresql://synthetic@127.0.0.1:5432/" + DATABASE,
                "jdbc:postgresql://synthetic:synthetic@127.0.0.1:5432/" + DATABASE}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", url);
            assertInvalidEnvironment(environment);
        }
    }

    @Test
    void rejectsUrlOverridesOfOperatorScopeAndBoundedDriverSettings() {
        String base = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
        for (String parameter : new String[]{
                "options=-c%20role%3Dsynthetic",
                "%6fptions=-c%20role%3Dsynthetic",
                "currentSchema=other_schema",
                "%63urrentSchema=other_schema",
                "connectTimeout=0",
                "%63onnectTimeout=0",
                "socketTimeout=0",
                "loginTimeout=0"}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", base + "?" + parameter);
            assertInvalidEnvironment(environment);
        }
    }

    @Test
    void requiresVerifiedHostnameTlsForNonloopbackDatabaseTargets() {
        String base = "jdbc:postgresql://postgres.example.test:5432/" + DATABASE;
        for (String suffix : new String[]{"", "?sslmode=disable", "?sslmode=allow", "?sslmode=prefer",
                "?sslmode=require", "?sslmode=verify-ca", "?sslmode=", "?sslmode=verify-full%0A"}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", base + suffix);
            assertInvalidEnvironment(environment);
        }
        for (String suffix : new String[]{"?sslmode=verify-full",
                "?sslmode=verify-full&sslrootcert=%2Fsynthetic%2Ftrusted-ca.pem"}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", base + suffix);
            assertThatCode(() -> FirstAdministratorTool.fromEnvironment(environment)).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsAmbiguousRepeatedTlsParametersBeforeDriverParsing() {
        String base = "jdbc:postgresql://postgres.example.test:5432/" + DATABASE;
        for (String query : new String[]{
                "sslmode=verify-full&sslmode=disable",
                "sslmode=disable&sslmode=verify-full",
                "sslmode=verify-full&%73slmode=disable",
                "sslmode=verify-full&sslmode=verify-full",
                "sslmode=verify-full&sslrootcert=synthetic-one&sslrootcert=synthetic-two"}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", base + "?" + query);
            assertInvalidEnvironment(environment);
        }
    }

    @Test
    void rejectsEncodedTlsKeysThatTheActualDriverWouldNotRecognize() {
        String base = "jdbc:postgresql://postgres.example.test:5432/" + DATABASE;
        for (String query : new String[]{
                "%73slmode=verify-full",
                "ssl%6dode=verify-full",
                "%73slrootcert=%2Fsynthetic%2Ftrusted-ca.pem",
                "sslmode=verify-full&%73slrootcert=%2Fsynthetic%2Ftrusted-ca.pem",
                "sslmode=verify-full&ssl%72ootcert=%2Fsynthetic%2Ftrusted-ca.pem"}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", base + "?" + query);
            assertInvalidEnvironment(environment);
        }
        // JDBC decodes parameter values, but not names. Preserve supported value
        // encoding without accepting a key that silently loses TLS/CA enforcement.
        var encodedValues = validEnvironment();
        encodedValues.put("MIGRATION_DATABASE_URL", base + "?sslmode=%76erify-full&sslrootcert=%2Fsynthetic%2Ftrusted-ca.pem");
        assertThatCode(() -> FirstAdministratorTool.fromEnvironment(encodedValues)).doesNotThrowAnyException();
    }

    @Test
    void permitsExplicitLoopbackRehearsalWithoutClaimingRemoteTlsAcceptance() {
        for (String host : new String[]{"127.0.0.1", "localhost", "[::1]"}) {
            var environment = validEnvironment();
            environment.put("MIGRATION_DATABASE_URL", "jdbc:postgresql://" + host + ":5432/" + DATABASE);
            assertThatCode(() -> FirstAdministratorTool.fromEnvironment(environment)).doesNotThrowAnyException();
        }
    }

    @Test
    void refusesTheSameMigrationAndRuntimeLogin() {
        var environment = validEnvironment();
        environment.put("DATABASE_USER", environment.get("MIGRATION_DATABASE_USER"));
        assertInvalidEnvironment(environment);
    }

    @Test
    void requiresCanonicalExplicitScopeUuids() {
        for (String key : new String[]{"FIRST_ADMIN_ORGANISATION_ID", "FIRST_ADMIN_SCHOOL_ID", "FIRST_ADMIN_CAMPUS_ID"}) {
            for (String invalid : new String[]{"not-a-uuid", "1-1-1-1-1", " " + ORGANISATION,
                    ORGANISATION + " ", ORGANISATION + "\n"}) {
                var environment = validEnvironment();
                environment.put(key, invalid);
                assertInvalidEnvironment(environment);
            }
        }
    }

    @Test
    void rejectsControlCharactersInOperatorConfiguration() {
        for (String key : new String[]{"MIGRATION_DATABASE_USER", "DATABASE_USER", "FIRST_ADMIN_DATABASE_NAME",
                "FIRST_ADMIN_APPROVAL_REFERENCE"}) {
            var environment = validEnvironment();
            environment.put(key, environment.get(key) + "\nunsafe");
            assertInvalidEnvironment(environment);
        }
    }

    @Test
    void configurationStringDoesNotExposeDatabaseCredentials() {
        var environment = validEnvironment();
        String secret = environment.get("MIGRATION_DATABASE_PASSWORD");
        var settings = FirstAdministratorTool.fromEnvironment(environment);
        assertThat(settings.toString()).doesNotContain(secret);
    }

    @Test
    void normalizesEmailAndDisplayNameUsingTheExistingIdentityRules() {
        assertThat(FirstAdministratorTool.identity("  SYNTHETIC.ADMIN@Example.TEST  ", "  Synthetic administrator  ", "en"))
                .isEqualTo(FirstAdministratorTool.identity("synthetic.admin@example.test", "Synthetic administrator", "en"));
        assertThatCode(() -> FirstAdministratorTool.identity("synthetic.admin@example.test", "सिंथेटिक प्रशासक", "hi"))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesInvalidBlankOverlongOrControlledEmailInputs() {
        for (String email : new String[]{null, "", "   ", "not-an-email", "@example.test", "synthetic@",
                "synthetic admin@example.test", "synthetic\nadmin@example.test", "synthetic\u0000admin@example.test",
                "x".repeat(255) + "@example.test"}) {
            assertThatThrownBy(() -> FirstAdministratorTool.identity(email, "Synthetic administrator", "en"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void requiresABoundedNonblankDisplayNameWithoutControlCharacters() {
        assertThatCode(() -> FirstAdministratorTool.identity("synthetic.admin@example.test", "x".repeat(120), "en"))
                .doesNotThrowAnyException();
        for (String name : new String[]{null, "", "   ", "x".repeat(121), "Synthetic\nAdministrator",
                "Synthetic\tAdministrator", "Synthetic\u0000Administrator", "Synthetic\u007fAdministrator"}) {
            assertThatThrownBy(() -> FirstAdministratorTool.identity("synthetic.admin@example.test", name, "en"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void permitsOnlyExplicitEnglishOrHindiPreferences() {
        for (String language : new String[]{"en", "hi"}) {
            assertThatCode(() -> FirstAdministratorTool.identity("synthetic.admin@example.test", "Synthetic administrator", language))
                    .doesNotThrowAnyException();
        }
        for (String language : new String[]{null, "", "EN", "en-US", "hi-IN", "fr", "en\n"}) {
            assertThatThrownBy(() -> FirstAdministratorTool.identity("synthetic.admin@example.test", "Synthetic administrator", language))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsPasswordBoundariesWithoutNormalizingOrMutatingTheInput() {
        for (int length : new int[]{16, 200}) {
            char[] input = dummyPassword(length);
            char[] original = input.clone();
            try {
                assertThatCode(() -> FirstAdministratorTool.validatePassword(input)).doesNotThrowAnyException();
                assertThat(Arrays.equals(input, original)).isTrue();
            } finally {
                Arrays.fill(input, '\0');
                Arrays.fill(original, '\0');
            }
        }
    }

    @Test
    void rejectsNullShortLongBlankAndControlledPasswords() {
        assertThatThrownBy(() -> FirstAdministratorTool.validatePassword(null)).isInstanceOf(IllegalArgumentException.class);
        for (int length : new int[]{0, 15, 201}) {
            char[] input = dummyPassword(length);
            try {
                assertThatThrownBy(() -> FirstAdministratorTool.validatePassword(input)).isInstanceOf(IllegalArgumentException.class);
            } finally {
                Arrays.fill(input, '\0');
            }
        }
        char[] blank = new char[16];
        Arrays.fill(blank, ' ');
        assertThatThrownBy(() -> FirstAdministratorTool.validatePassword(blank)).isInstanceOf(IllegalArgumentException.class);
        for (char control : new char[]{'\0', '\n', '\r', '\t', '\u007f', '\u0085'}) {
            char[] input = dummyPassword(16);
            input[8] = control;
            try {
                assertThatThrownBy(() -> FirstAdministratorTool.validatePassword(input)).isInstanceOf(IllegalArgumentException.class);
            } finally {
                Arrays.fill(input, '\0');
            }
        }
    }

    @Test
    void demandsExactDeliberateDatabaseAndSchoolConfirmation() {
        var settings = FirstAdministratorTool.fromEnvironment(validEnvironment());
        String confirmation = settings.confirmation();
        assertThatCode(() -> FirstAdministratorTool.requireConfirmation(settings, confirmation)).doesNotThrowAnyException();
        for (String incorrect : new String[]{null, "", "yes", DATABASE, " " + confirmation, confirmation + " ",
                confirmation + "\n", confirmation.replace(ORGANISATION, UUID.randomUUID().toString()),
                confirmation.replace(SCHOOL, UUID.randomUUID().toString()),
                confirmation.replace(CAMPUS, UUID.randomUUID().toString())}) {
            assertThatThrownBy(() -> FirstAdministratorTool.requireConfirmation(settings, incorrect))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static void assertInvalidEnvironment(Map<String, String> environment) {
        assertThatThrownBy(() -> FirstAdministratorTool.fromEnvironment(environment)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> validEnvironment() {
        var environment = new HashMap<String, String>();
        environment.put("MIGRATION_DATABASE_URL", "jdbc:postgresql://127.0.0.1:5432/" + DATABASE);
        environment.put("MIGRATION_DATABASE_USER", "synthetic_migrator");
        environment.put("MIGRATION_DATABASE_PASSWORD", UUID.randomUUID().toString());
        environment.put("DATABASE_USER", "synthetic_runtime");
        environment.put("FIRST_ADMIN_DATABASE_NAME", DATABASE);
        environment.put("FIRST_ADMIN_ORGANISATION_ID", ORGANISATION);
        environment.put("FIRST_ADMIN_SCHOOL_ID", SCHOOL);
        environment.put("FIRST_ADMIN_CAMPUS_ID", CAMPUS);
        environment.put("FIRST_ADMIN_APPROVAL_REFERENCE", "SYNTHETIC-APPROVAL-" + UUID.randomUUID());
        return environment;
    }

    private static char[] dummyPassword(int length) {
        var random = new SecureRandom();
        char[] input = new char[length];
        for (int index = 0; index < input.length; index++) input[index] = (char) ('!' + random.nextInt(94));
        return input;
    }
}
