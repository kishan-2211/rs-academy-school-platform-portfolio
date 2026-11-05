package com.educore.api.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;

/** Real MinIO/ClamAV acceptance, selected only by the explicit Maven profile. */
class PrivateFilesIT {
    private static final String BUCKET = "educore-private-files-integration";
    private static final String OTHER_BUCKET = "educore-private-files-other-scope";
    private static FileStorageProperties properties;
    private static S3Client client;
    private static ObjectStorage storage;
    private static MalwareScanner scanner;

    @TempDir Path temporaryDirectory;

    @BeforeAll
    static void connectToIsolatedRealServices() throws Exception {
        assertThat(required("TEST_PRIVATE_FILES_ENABLED")).isEqualTo("true");
        URI endpoint = URI.create(required("TEST_S3_ENDPOINT"));
        assertThat(endpoint.getScheme()).isEqualTo("http");
        assertThat(endpoint.getHost()).isEqualTo("127.0.0.1");
        assertThat(endpoint.getUserInfo()).isNull();
        assertThat(endpoint.getQuery()).isNull();
        assertThat(endpoint.getPath()).isIn("", "/");
        assertThat(required("TEST_S3_BUCKET")).isEqualTo(BUCKET);
        // Compare booleans so a configuration error cannot print credentials.
        assertThat(required("PRIVATE_FILES_TEST_ACCESS_KEY")
                .equals(required("PRIVATE_FILES_TEST_ROOT_USER"))).isFalse();
        assertThat(required("PRIVATE_FILES_TEST_SECRET_KEY")
                .equals(required("PRIVATE_FILES_TEST_ROOT_PASSWORD"))).isFalse();
        String scannerHost = required("TEST_CLAMAV_HOST");
        assertThat(scannerHost).isEqualTo("127.0.0.1");
        var clamav = new FileStorageProperties.ClamAv(scannerHost,
                Integer.parseInt(required("TEST_CLAMAV_PORT")), Duration.ofSeconds(2), Duration.ofSeconds(30));
        properties = new FileStorageProperties(endpoint, "us-east-1", BUCKET,
                required("PRIVATE_FILES_TEST_ACCESS_KEY"), required("PRIVATE_FILES_TEST_SECRET_KEY"),
                Duration.ofSeconds(2), Duration.ofSeconds(30), true, false,
                10_485_760, Set.of("application/pdf"), clamav);
        var configuration = new FileInfrastructureConfiguration();
        client = configuration.privateFileS3Client(properties);
        storage = configuration.privateObjectStorage(client, properties);
        scanner = configuration.privateFileMalwareScanner(properties);
        // Missing/unready dependencies fail, rather than disable or mock these tests.
        storage.verifyAvailable();
        scanner.verifyAvailable();
    }

    @AfterAll
    static void closeClient() {
        if (client != null) client.close();
    }

    @Test
    void realScannerAcceptsGeneratedPdfAndQuarantineCleansUp() throws Exception {
        Path quarantined;
        try (var inspected = quarantine(scanner).inspect(pdfUpload())) {
            quarantined = inspected.path();
            assertThat(inspected.scanEngine()).isEqualTo("ClamAV");
            assertThat(inspected.scanEngineVersion()).startsWith("ClamAV ");
            assertThat(inspected.mediaType()).isEqualTo("application/pdf");
            assertThat(inspected.sha256Hex()).matches("[a-f0-9]{64}");
            assertThat(inspected.downloadFilename()).isEqualTo("synthetic-report.pdf");
            assertThat(Files.exists(quarantined)).isTrue();
        }
        assertThat(Files.exists(quarantined)).isFalse();
    }

    @Test
    void realPrivateStoragePreservesScannedBytesAndDeniesAnonymousRead() throws Exception {
        String key = "integration/" + UUID.randomUUID() + "/synthetic-report.pdf";
        try (var inspected = quarantine(scanner).inspect(pdfUpload())) {
            try {
                storage.put(key, inspected.path(), inspected.mediaType(), inspected.sizeBytes(), inspected.sha256Hex());
                var downloaded = new ByteArrayOutputStream();
                storage.copyTo(key, downloaded);
                assertThat(downloaded.toByteArray()).isEqualTo(Files.readAllBytes(inspected.path()));
                var head = client.headObject(request -> request.bucket(BUCKET).key(key));
                assertThat(head.metadata().get("sha256")).isEqualTo(inspected.sha256Hex());
                assertThat(head.contentLength()).isEqualTo(inspected.sizeBytes());
                var verified = new ByteArrayOutputStream();
                storage.copyVerifiedTo(key, inspected.sizeBytes(), inspected.sha256Hex(), verified);
                assertThat(verified.toByteArray()).isEqualTo(downloaded.toByteArray());
                URI unsigned = properties.endpoint().resolve("/" + BUCKET + "/" + key);
                try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
                    var response = http.send(HttpRequest.newBuilder(unsigned).timeout(Duration.ofSeconds(5)).GET().build(),
                            HttpResponse.BodyHandlers.discarding());
                    assertThat(response.statusCode()).isEqualTo(403);
                }
            } finally {
                // Only this test's random object is removed; no production bucket/listing is used.
                storage.delete(key);
            }
        }
        assertThatThrownBy(() -> storage.copyTo(key, new ByteArrayOutputStream()))
                .isInstanceOf(FileInfrastructureException.class);
    }

    @Test
    void realStorageRejectsAnIncorrectSha256() throws Exception {
        String key = "integration/" + UUID.randomUUID() + "/bad-checksum.pdf";
        try (var inspected = quarantine(scanner).inspect(pdfUpload())) {
            try {
                assertThatThrownBy(() -> storage.put(key, inspected.path(), inspected.mediaType(),
                        inspected.sizeBytes(), "0".repeat(64)))
                        .isInstanceOf(FileInfrastructureException.class);
            } finally {
                storage.delete(key);
            }
        }
    }

    @Test
    void realStorageValidChecksumCannotReplaceTheRecordedDatabaseDigest() throws Exception {
        String key = "integration/" + UUID.randomUUID() + "/synthetic-restore-mismatch.pdf";
        try (var inspected = quarantine(scanner).inspect(pdfUpload())) {
            byte[] substituted = Files.readAllBytes(inspected.path());
            substituted[0] ^= 1;
            String wrongDigest = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(substituted));
            Path wrong = temporaryDirectory.resolve("synthetic-substituted-bytes");
            Files.write(wrong, substituted);
            try {
                // Valid checksum for the wrong same-sized object: transport/storage
                // validation alone would pass, but the recorded school digest must not.
                storage.put(key, wrong, inspected.mediaType(), substituted.length, wrongDigest);
                var unverified = new ByteArrayOutputStream();
                storage.copyTo(key, unverified);
                assertThat(unverified.toByteArray()).isEqualTo(substituted);
                var protectedOutput = new ByteArrayOutputStream();
                assertThatThrownBy(() -> storage.copyVerifiedTo(key, inspected.sizeBytes(),
                        inspected.sha256Hex(), protectedOutput)).isInstanceOf(FileInfrastructureException.class);
                assertThat(protectedOutput.size()).isZero();
            } finally { storage.delete(key); }
        }
    }

    @Test
    void realStorageMissingOrWrongSizedRestoreNeverReleasesPrivateBytes() throws Exception {
        String key = "integration/" + UUID.randomUUID() + "/synthetic-restore-size.pdf";
        try (var inspected = quarantine(scanner).inspect(pdfUpload())) {
            try {
                storage.put(key, inspected.path(), inspected.mediaType(), inspected.sizeBytes(), inspected.sha256Hex());
                for (long recordedSize : new long[] { inspected.sizeBytes() - 1, inspected.sizeBytes() + 1 }) {
                    var output = new ByteArrayOutputStream();
                    assertThatThrownBy(() -> storage.copyVerifiedTo(key, recordedSize, inspected.sha256Hex(), output))
                            .isInstanceOf(FileInfrastructureException.class);
                    assertThat(output.size()).isZero();
                }
            } finally { storage.delete(key); }
            var missing = new ByteArrayOutputStream();
            assertThatThrownBy(() -> storage.copyVerifiedTo(key, inspected.sizeBytes(), inspected.sha256Hex(), missing))
                    .isInstanceOf(FileInfrastructureException.class);
            assertThat(missing.size()).isZero();
        }
    }

    @Test
    void realLimitedStorageCredentialsCannotAccessAnotherBucket() {
        assertAccessDenied(() -> client.headBucket(request -> request.bucket(OTHER_BUCKET)));
        assertAccessDenied(() -> client.getObjectAsBytes(request -> request.bucket(OTHER_BUCKET)
                .key("synthetic-other-scope.txt")));
        assertAccessDenied(() -> client.putObject(request -> request.bucket(OTHER_BUCKET)
                .key("synthetic-other-scope.txt"), RequestBody.fromString("Unauthorized synthetic write")));
        assertAccessDenied(() -> client.deleteObject(request -> request.bucket(OTHER_BUCKET)
                .key("synthetic-other-scope.txt")));
        // AWS denies ListAllMyBuckets; this MinIO release instead filters the
        // response to buckets with ListBucket permission. Neither may expose
        // the second synthetic bucket (a root account would expose both).
        try {
            assertThat(client.listBuckets().buckets()).extracting(Bucket::name).containsExactly(BUCKET);
        } catch (S3Exception denied) {
            assertThat(denied.statusCode()).isEqualTo(403);
        }
        // Denied calls must not impair the explicitly permitted bucket.
        storage.verifyAvailable();
    }

    @Test
    void realLimitedStorageCredentialsCannotAdministerBuckets() {
        // Every target is this invocation's disposable fixture, never a school bucket.
        assertAccessDenied(() -> client.createBucket(request -> request
                .bucket("educore-files-denied-" + UUID.randomUUID())));
        assertAccessDenied(() -> client.deleteBucket(request -> request.bucket(BUCKET)));
        assertAccessDenied(() -> client.putBucketVersioning(request -> request.bucket(BUCKET)
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED).build())));
        // This policy would DENY anonymous reads even if the credential were root;
        // never grant public access merely to test whether policy changes are denied.
        String restrictivePolicy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Deny","Principal":"*",
                "Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}
                """.formatted(BUCKET);
        assertAccessDenied(() -> client.putBucketPolicy(request -> request.bucket(BUCKET)
                .policy(restrictivePolicy)));
        storage.verifyAvailable();
    }

    @Test
    void realScannerDetectsTheHarmlessStandardAntivirusTestSignature() throws Exception {
        // Standard EICAR test bytes, not malware. Keep them encoded in source and
        // instantiate only in this isolated real-service gate, never in school data.
        byte[] signature = Base64.getDecoder().decode(
                "WDVPIVAlQEFQWzRcUFpYNTQoUF4pN0NDKTd9JEVJQ0FSLVNUQU5EQVJELUFOVElWSVJVUy1URVNULUZJTEUhJEgrSCo=");
        assertThat(signature).hasSize(68);
        Path file = temporaryDirectory.resolve("synthetic-antivirus-test.txt");
        Files.write(file, signature);
        var result = scanner.scan(file);
        assertThat(result.engine()).isEqualTo("ClamAV");
        assertThat(result.clean()).isFalse();
        assertThat(result.threatName()).containsIgnoringCase("Eicar");
    }

    @Test
    void unreachableScannerFailsClosedForAGeneratedPdf() throws Exception {
        int unusedPort;
        try (var reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = reserved.getLocalPort();
        }
        MalwareScanner unavailable = new ClamAvMalwareScanner(new FileStorageProperties.ClamAv(
                "127.0.0.1", unusedPort, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThatThrownBy(() -> quarantine(unavailable).inspect(pdfUpload()))
                .isInstanceOf(FileInfrastructureException.class);
    }

    private static FileQuarantineService quarantine(MalwareScanner scanner) {
        return new FileQuarantineService(properties, scanner);
    }

    private static void assertAccessDenied(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(S3Exception.class,
                denied -> assertThat(denied.statusCode()).isEqualTo(403));
    }

    private static MockMultipartFile pdfUpload() throws Exception {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(60, 720);
                content.showText("Synthetic private-file acceptance only. No school or personal data.");
                content.endText();
            }
            document.save(bytes);
            return new MockMultipartFile("file", "../../synthetic-report.exe",
                    "application/octet-stream", bytes.toByteArray());
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configure isolated private-file test services: missing " + name);
        }
        return value;
    }
}
