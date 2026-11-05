package com.educore.api.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.common.api.ApiException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class FileQuarantineServiceTest {

    @Test
    void trustsMagicBytesAndNormalizesAnUntrustedFilename() throws Exception {
        var service = service(path -> new MalwareScanner.ScanResult("SyntheticAV", "1.0", true, null));
        var upload = new MockMultipartFile(
                "file",
                "../../synthetic report.exe",
                "application/octet-stream",
                "%PDF-1.7\nsynthetic".getBytes(StandardCharsets.US_ASCII));

        java.nio.file.Path quarantinedPath;
        try (var file = service.inspect(upload)) {
            quarantinedPath = file.path();
            assertThat(file.mediaType()).isEqualTo("application/pdf");
            assertThat(file.downloadFilename()).isEqualTo("synthetic report.pdf");
            assertThat(file.sha256Hex()).matches("[a-f0-9]{64}");
            assertThat(Files.exists(file.path())).isTrue();
        }
        assertThat(Files.exists(quarantinedPath)).isFalse();
    }

    @Test
    void rejectsAClaimedPdfWithoutPdfMagicBytes() {
        var service = service(path -> new MalwareScanner.ScanResult("SyntheticAV", "1.0", true, null));
        var upload = new MockMultipartFile(
                "file", "unsafe.pdf", "application/pdf", "<html>active</html>".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.inspect(upload))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FILE_TYPE_NOT_ALLOWED"));
    }

    @Test
    void rejectsMalwareBeforeObjectStorage() {
        var service = service(path -> new MalwareScanner.ScanResult(
                "SyntheticAV", "1.0", false, "Synthetic-Test-Signature"));
        var upload = new MockMultipartFile(
                "file", "synthetic.pdf", "application/pdf", "%PDF-1.7\ntest".getBytes(StandardCharsets.US_ASCII));

        assertThatThrownBy(() -> service.inspect(upload))
                .isInstanceOfSatisfying(FileQuarantineService.MalwareDetectedException.class, exception -> {
                    assertThat(exception.sha256Hex()).matches("[a-f0-9]{64}");
                    assertThat(exception.threatName()).isEqualTo("Synthetic-Test-Signature");
                });
    }

    private static FileQuarantineService service(MalwareScanner scanner) {
        var properties = new FileStorageProperties(
                URI.create("http://localhost:9000"),
                "ap-south-1",
                "synthetic-private",
                "synthetic-access",
                "synthetic-secret",
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                true,
                true,
                10_485_760,
                Set.of("application/pdf", "image/jpeg", "image/png", "image/webp"),
                new FileStorageProperties.ClamAv(
                        "localhost", 3310, Duration.ofSeconds(1), Duration.ofSeconds(5)));
        return new FileQuarantineService(properties, scanner);
    }
}
