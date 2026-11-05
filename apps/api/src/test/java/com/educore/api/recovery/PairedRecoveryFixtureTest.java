package com.educore.api.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PairedRecoveryFixtureTest {
    private Map<String, String> environment() {
        var values = new HashMap<>(Map.of("TEST_PAIRED_RECOVERY_ENABLED", "true", "TEST_PRIVATE_FILES_ENABLED", "true",
                "TEST_PAIRED_DATABASE_URL", "jdbc:postgresql://127.0.0.1:15432/educore_paired_integration",
                "TEST_PAIRED_DATABASE_USER", "educore_paired_test", "TEST_PAIRED_DATABASE_PASSWORD", UUID.randomUUID().toString(),
                "TEST_PAIRED_POSTGRES_CONTAINER", "a".repeat(64)));
        values.putAll(Map.of("TEST_S3_ENDPOINT", "http://127.0.0.1:19000", "TEST_S3_BUCKET", "educore-private-files-integration",
                "TEST_CLAMAV_HOST", "127.0.0.1", "TEST_CLAMAV_PORT", "13310",
                "PRIVATE_FILES_TEST_ACCESS_KEY", UUID.randomUUID().toString(), "PRIVATE_FILES_TEST_SECRET_KEY", UUID.randomUUID().toString(),
                "PRIVATE_FILES_TEST_ROOT_USER", UUID.randomUUID().toString(), "PRIVATE_FILES_TEST_ROOT_PASSWORD", UUID.randomUUID().toString()));
        return values;
    }

    @Test void requiresBothExplicitOptIns() {
        for (String key : new String[]{"TEST_PAIRED_RECOVERY_ENABLED", "TEST_PRIVATE_FILES_ENABLED"}) {
            var values = environment(); values.remove(key);
            assertThatThrownBy(() -> PairedRecoveryFixture.validate(values)).isInstanceOf(IllegalArgumentException.class);
            values.put(key, "false");
            assertThatThrownBy(() -> PairedRecoveryFixture.validate(values)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsProductionRemoteQueryAndCredentialBearingDatabaseUrls() {
        for (String url : new String[]{"jdbc:postgresql://127.0.0.1:5432/educore",
                "jdbc:postgresql://localhost:15432/educore_paired_integration",
                "jdbc:postgresql://database.example.test:15432/educore_paired_integration",
                "jdbc:postgresql://127.0.0.1:15432/educore_paired_integration?sslmode=disable",
                "jdbc:postgresql://127.0.0.1:15432/educore_paired_integration?user=other",
                "jdbc:postgresql://127.0.0.1:99999/educore_paired_integration"}) {
            var values = environment(); values.put("TEST_PAIRED_DATABASE_URL", url);
            assertThatThrownBy(() -> PairedRecoveryFixture.validate(values)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsMissingCredentialsWrongOwnerAndContainerAliases() {
        for (String key : new String[]{"TEST_PAIRED_DATABASE_PASSWORD", "TEST_PAIRED_DATABASE_USER", "TEST_PAIRED_POSTGRES_CONTAINER"}) {
            var values = environment(); values.put(key, "");
            assertThatThrownBy(() -> PairedRecoveryFixture.validate(values)).isInstanceOf(IllegalArgumentException.class);
        }
        var wrongOwner = environment(); wrongOwner.put("TEST_PAIRED_DATABASE_USER", "educore");
        assertThatThrownBy(() -> PairedRecoveryFixture.validate(wrongOwner)).isInstanceOf(IllegalArgumentException.class);
        var alias = environment(); alias.put("TEST_PAIRED_POSTGRES_CONTAINER", "school-postgres");
        assertThatThrownBy(() -> PairedRecoveryFixture.validate(alias)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceptsOnlyTheCompleteDedicatedFixture() {
        PairedRecoveryFixture.validate(environment());
    }

    @Test void rejectsSchoolStorageRemoteScannerAndRootStorageCredentials() {
        for (var invalid : Map.of("TEST_S3_ENDPOINT", "https://storage.example.test",
                "TEST_S3_BUCKET", "school-private", "TEST_CLAMAV_HOST", "scanner.example.test", "TEST_CLAMAV_PORT", "99999").entrySet()) {
            var values = environment(); values.put(invalid.getKey(), invalid.getValue());
            assertThatThrownBy(() -> PairedRecoveryFixture.validate(values)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String endpoint : new String[]{"http://127.0.0.1:19000/other", "http://127.0.0.1:19000?bucket=school",
                "http://root@127.0.0.1:19000", "http://127.0.0.1:15432", "http://127.0.0.1:13310"}) {
            var values = environment(); values.put("TEST_S3_ENDPOINT", endpoint);
            assertThatThrownBy(() -> PairedRecoveryFixture.validate(values)).isInstanceOf(IllegalArgumentException.class);
        }
        var rootCredentials = environment(); rootCredentials.put("PRIVATE_FILES_TEST_ACCESS_KEY", rootCredentials.get("PRIVATE_FILES_TEST_ROOT_USER"));
        assertThatThrownBy(() -> PairedRecoveryFixture.validate(rootCredentials)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void generatedTargetsCannotPointToExistingOrOtherInvocationDatabases() {
        UUID invocation = UUID.randomUUID();
        String source = PairedRecoveryFixture.database("source", invocation);
        String restore = PairedRecoveryFixture.database("restore", invocation);
        assertThat(source).isNotEqualTo(restore);
        assertThat(source.length()).isLessThan(63);
        PairedRecoveryFixture.requireDatabase(source, "source", invocation);
        PairedRecoveryFixture.requireDatabase(restore, "restore", invocation);
        for (String target : new String[]{"educore", source, "educore_browser_acceptance_20260927",
                PairedRecoveryFixture.database("restore", UUID.randomUUID())}) {
            assertThatThrownBy(() -> PairedRecoveryFixture.requireDatabase(target, "restore", invocation))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PairedRecoveryFixture.database("production", invocation))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
