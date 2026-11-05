package com.educore.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ApiRuntimePackagingTest {
    @Test
    void extractedToolsLayoutStartsItsRealApplicationJarRatherThanAnAbsentLauncher() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertThat(dockerfile).contains("java -Djarmode=tools", "extract --layers", "extracted/application/ ./",
                "\"-jar\", \"educore-api-0.1.0-SNAPSHOT.jar\"")
                .doesNotContain("\"org.springframework.boot.loader.launch.JarLauncher\"");
    }

    @Test
    void runtimeRetainsItsNonRootIdentityAndOwnedLayers() throws Exception {
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertThat(dockerfile).contains("-u 10001", "USER educore", "--chown=educore:educore",
                "extracted/dependencies/ ./", "extracted/snapshot-dependencies/ ./");
    }
}
