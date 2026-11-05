package com.educore.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Configuration regression checks; real effective permissions belong to PrivateFilesIT. */
class PrivateStorageDeploymentTest {
    private static final Path ROOT = Path.of("../..");

    @Test
    void apiComposeReceivesDedicatedCredentialsNotStorageAdministratorCredentials() throws Exception {
        String compose = Files.readString(ROOT.resolve("infra/compose.yaml"));
        String api = compose.substring(compose.indexOf("\n  api:"), compose.indexOf("\n  web:"));
        assertThat(api).contains("S3_ACCESS_KEY: ${S3_ACCESS_KEY:?", "S3_SECRET_KEY: ${S3_SECRET_KEY:?",
                "S3_AUTO_CREATE_BUCKET: \"false\"").doesNotContain("MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD");
    }

    @Test
    void runtimePolicyAllowsOnlyCurrentAdapterOperationsInOneNamedPrivateBucket() throws Exception {
        String policy = Files.readString(ROOT.resolve("infra/policies/private-files-runtime-policy.json"))
                .replace("__BUCKET_NAME__", "synthetic-private-bucket");
        var document = new ObjectMapper().readTree(policy);
        assertThat(document.path("Version").asString()).isEqualTo("2012-10-17");
        var statements = document.path("Statement");
        assertThat(statements.size()).isEqualTo(2);
        assertThat(statements.get(0).path("Effect").asString()).isEqualTo("Allow");
        assertThat(statements.get(0).path("Action").size()).isEqualTo(1);
        assertThat(statements.get(0).path("Action").get(0).asString()).isEqualTo("s3:ListBucket");
        assertThat(statements.get(0).path("Resource").size()).isEqualTo(1);
        assertThat(statements.get(0).path("Resource").get(0).asString())
                .isEqualTo("arn:aws:s3:::synthetic-private-bucket");
        assertThat(statements.get(1).path("Effect").asString()).isEqualTo("Allow");
        assertThat(statements.get(1).path("Action").size()).isEqualTo(3);
        assertThat(statements.get(1).path("Action").get(0).asString()).isEqualTo("s3:GetObject");
        assertThat(statements.get(1).path("Action").get(1).asString()).isEqualTo("s3:PutObject");
        assertThat(statements.get(1).path("Action").get(2).asString()).isEqualTo("s3:DeleteObject");
        assertThat(statements.get(1).path("Resource").size()).isEqualTo(1);
        assertThat(statements.get(1).path("Resource").get(0).asString())
                .isEqualTo("arn:aws:s3:::synthetic-private-bucket/*");
        assertThat(policy).doesNotContain("__BUCKET_NAME__", "s3:*", "admin:", "Principal", "NotAction");
    }
}
