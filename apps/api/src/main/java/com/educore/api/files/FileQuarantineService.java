package com.educore.api.files;

import com.educore.api.common.api.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class FileQuarantineService {

    private final FileStorageProperties properties;
    private final MalwareScanner scanner;
    private final FileTypeDetector detector = new FileTypeDetector();

    FileQuarantineService(FileStorageProperties properties, MalwareScanner scanner) {
        this.properties = properties;
        this.scanner = scanner;
    }

    QuarantinedFile inspect(MultipartFile upload) {
        if (upload == null || upload.isEmpty() || upload.getSize() < 1) {
            throw badRequest("FILE_EMPTY", "Choose a non-empty file to upload.");
        }
        if (upload.getSize() > properties.maxSizeBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "The file exceeds the configured private upload limit.");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("educore-quarantine-", ".upload");
            upload.transferTo(temporary);
            long actualSize = Files.size(temporary);
            if (actualSize < 1 || actualSize > properties.maxSizeBytes()) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                        "The file exceeds the configured private upload limit.");
            }
            String mediaType = detector.detect(temporary);
            if (mediaType == null || !properties.allowedMediaTypes().contains(mediaType)) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "FILE_TYPE_NOT_ALLOWED",
                        "This file type is not allowed. Use PDF, JPEG, PNG, or WebP.");
            }
            String sha256 = digest(temporary);
            MalwareScanner.ScanResult scan = scanner.scan(temporary);
            if (!scan.clean()) {
                throw new MalwareDetectedException(sha256, scan.threatName());
            }
            String original = safeOriginalFilename(upload.getOriginalFilename());
            String download = safeDownloadFilename(original, detector.extension(mediaType));
            return new QuarantinedFile(temporary, original, download, mediaType, actualSize, sha256,
                    scan.engine(), scan.engineVersion());
        } catch (ApiException | MalwareDetectedException | FileInfrastructureException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException exception) {
            deleteQuietly(temporary);
            throw new FileInfrastructureException("The upload could not be quarantined", exception);
        }
    }

    private static String digest(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new FileInfrastructureException("The file checksum could not be calculated", exception);
        }
    }

    private static String safeOriginalFilename(String value) {
        String candidate = value == null ? "upload" : value.replace('\\', '/');
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0) candidate = candidate.substring(slash + 1);
        candidate = Normalizer.normalize(candidate, Normalizer.Form.NFC)
                .replaceAll("[\\p{Cntrl}]", " ").strip();
        if (candidate.isEmpty()) candidate = "upload";
        return candidate.substring(0, Math.min(candidate.length(), 240));
    }

    private static String safeDownloadFilename(String original, String expectedExtension) {
        String value = original.replaceAll("[^\\p{L}\\p{N} ._()\\-]", "_")
                .replaceAll("\\s+", " ").replaceAll("\\.{2,}", ".").strip();
        while (value.startsWith(".")) value = value.substring(1);
        if (value.isBlank()) value = "download";
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(expectedExtension)) {
            int dot = value.lastIndexOf('.');
            if (dot > 0) value = value.substring(0, dot);
            value += expectedExtension;
        }
        if (value.length() > 180) {
            value = value.substring(0, 180 - expectedExtension.length()).strip() + expectedExtension;
        }
        return value;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // The operating-system temp cleaner is the final fallback for a failed quarantine cleanup.
        }
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    record QuarantinedFile(
            Path path,
            String originalFilename,
            String downloadFilename,
            String mediaType,
            long sizeBytes,
            String sha256Hex,
            String scanEngine,
            String scanEngineVersion) implements AutoCloseable {
        @Override
        public void close() {
            deleteQuietly(path);
        }
    }

    static final class MalwareDetectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String sha256Hex;
        private final String threatName;

        MalwareDetectedException(String sha256Hex, String threatName) {
            super("Malware scanner rejected the upload");
            this.sha256Hex = sha256Hex;
            this.threatName = threatName;
        }

        String sha256Hex() { return sha256Hex; }
        String threatName() { return threatName; }
    }
}
