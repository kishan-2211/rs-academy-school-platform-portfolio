package com.educore.api.files;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FileStoragePropertiesTest {

    @Test
    void rejectsUnboundedUploadsAndScannerTimeouts() {
        assertThatThrownBy(() -> properties(52_428_801, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(10_485_760, Duration.ofMinutes(3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static FileStorageProperties properties(long size, Duration scannerReadTimeout) {
        return new FileStorageProperties(
                URI.create("http://localhost:9000"),
                "ap-south-1",
                "synthetic-private",
                "synthetic-access",
                "synthetic-secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                true,
                false,
                size,
                Set.of("application/pdf"),
                new FileStorageProperties.ClamAv(
                        "localhost", 3310, Duration.ofSeconds(1), scannerReadTimeout));
    }
}
