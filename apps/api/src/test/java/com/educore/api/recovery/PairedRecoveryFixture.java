package com.educore.api.recovery;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.net.URI;

/** Fail-closed boundaries for the explicitly disposable recovery gate, not an operator restore tool. */
public final class PairedRecoveryFixture {
    private static final Pattern URL = Pattern.compile(
            "jdbc:postgresql://127\\.0\\.0\\.1:([0-9]{4,5})/educore_paired_integration");
    private PairedRecoveryFixture() {}

    public static void validate(Map<String, String> environment) {
        if (!"true".equals(environment.get("TEST_PAIRED_RECOVERY_ENABLED"))
                || !"true".equals(environment.get("TEST_PRIVATE_FILES_ENABLED"))) {
            throw new IllegalArgumentException("Explicit isolated recovery and private-service opt-in required");
        }
        var match = URL.matcher(required(environment, "TEST_PAIRED_DATABASE_URL"));
        if (!match.matches()) throw new IllegalArgumentException("Only the loopback disposable recovery database is permitted");
        int port = Integer.parseInt(match.group(1));
        if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid isolated recovery database port");
        if (!"educore_paired_test".equals(required(environment, "TEST_PAIRED_DATABASE_USER"))) {
            throw new IllegalArgumentException("Only the dedicated recovery test owner is permitted");
        }
        required(environment, "TEST_PAIRED_DATABASE_PASSWORD");
        if (!required(environment, "TEST_PAIRED_POSTGRES_CONTAINER").matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("The exact invocation-owned PostgreSQL container is required");
        }
        URI endpoint;
        try { endpoint = URI.create(required(environment, "TEST_S3_ENDPOINT")); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid isolated storage endpoint"); }
        if (!"http".equals(endpoint.getScheme()) || !"127.0.0.1".equals(endpoint.getHost())
                || endpoint.getPort() < 1024 || endpoint.getPort() > 65535 || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !"".equals(endpoint.getPath()) && !"/".equals(endpoint.getPath())
                || !"educore-private-files-integration".equals(required(environment, "TEST_S3_BUCKET"))) {
            throw new IllegalArgumentException("Only the loopback disposable private storage fixture is permitted");
        }
        if (!"127.0.0.1".equals(required(environment, "TEST_CLAMAV_HOST"))
                || !required(environment, "TEST_CLAMAV_PORT").matches("[0-9]{4,5}")) {
            throw new IllegalArgumentException("Only the loopback disposable scanner is permitted");
        }
        int scannerPort = Integer.parseInt(required(environment, "TEST_CLAMAV_PORT"));
        if (scannerPort < 1024 || scannerPort > 65535 || scannerPort == port || endpoint.getPort() == port || endpoint.getPort() == scannerPort) {
            throw new IllegalArgumentException("Distinct valid isolated service ports are required");
        }
        if (required(environment, "PRIVATE_FILES_TEST_ACCESS_KEY").equals(required(environment, "PRIVATE_FILES_TEST_ROOT_USER"))
                || required(environment, "PRIVATE_FILES_TEST_SECRET_KEY").equals(required(environment, "PRIVATE_FILES_TEST_ROOT_PASSWORD"))) {
            throw new IllegalArgumentException("Only limited test storage credentials are permitted");
        }
    }

    public static String database(String kind, UUID invocation) {
        if (!kind.equals("source") && !kind.equals("restore")) throw new IllegalArgumentException("Unsupported recovery fixture kind");
        return "educore_paired_" + kind + "_" + invocation.toString().replace("-", "");
    }

    public static void requireDatabase(String name, String kind, UUID invocation) {
        if (!database(kind, invocation).equals(name)) throw new IllegalArgumentException("Refusing an unowned database target");
    }

    public static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing isolated test binding: " + name);
        return value;
    }
}
