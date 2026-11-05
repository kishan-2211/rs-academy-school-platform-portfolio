package com.educore.api.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class VerifiedFileCopyTest {
    private static final byte[] BYTES = "Synthetic private bytes only".getBytes(StandardCharsets.UTF_8);
    @TempDir Path directory;

    @Test
    void releasesOnlyCompleteVerifiedBytesAndLeavesTheCallerOutputOpen() throws Exception {
        var closed = new AtomicBoolean();
        var target = new ByteArrayOutputStream() {
            @Override public void close() { closed.set(true); }
        };
        for (int retry = 0; retry < 2; retry++) {
            int previousSize = target.size();
            VerifiedFileCopy.copy(BYTES.length, sha256(BYTES), target, staged -> {
                // No output has escaped before the storage operation finishes.
                assertThat(target.size()).isEqualTo(previousSize);
                try (var paths = Files.list(directory)) {
                    var files = paths.toList();
                    assertThat(files).hasSize(1);
                    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
                        assertThat(Files.getPosixFilePermissions(files.getFirst())).containsExactlyInAnyOrder(
                                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
                    }
                } catch (IOException exception) { throw new IllegalStateException(exception); }
                write(staged, BYTES);
                assertThat(target.size()).isEqualTo(previousSize);
            }, directory);
        }
        assertThat(target.toByteArray()).isEqualTo(concat(BYTES, BYTES));
        assertThat(closed).isFalse();
        assertEmptyDirectory();
    }

    @Test
    void wrongBytesOfTheSameLengthNeverReachTheCaller() throws Exception {
        var target = new ByteArrayOutputStream();
        byte[] wrong = BYTES.clone();
        wrong[0] ^= 1;
        assertThatThrownBy(() -> VerifiedFileCopy.copy(BYTES.length, sha256(BYTES), target,
                staged -> write(staged, wrong), directory))
                .isInstanceOf(FileInfrastructureException.class).hasMessage("Private file integrity verification failed");
        assertThat(target.size()).isZero();
        assertEmptyDirectory();
    }

    @Test
    void truncatedBytesNeverReachTheCaller() throws Exception {
        var target = new ByteArrayOutputStream();
        assertThatThrownBy(() -> VerifiedFileCopy.copy(BYTES.length + 1, sha256(BYTES), target,
                staged -> write(staged, BYTES), directory)).isInstanceOf(FileInfrastructureException.class);
        assertThat(target.size()).isZero();
        assertEmptyDirectory();
    }

    @Test
    void extraBytesStopAtTheRecordedBoundWithoutReleasingAPrefix() throws Exception {
        var target = new ByteArrayOutputStream();
        assertThatThrownBy(() -> VerifiedFileCopy.copy(BYTES.length, sha256(BYTES), target, staged -> {
            write(staged, BYTES);
            write(staged, new byte[] { 1 });
        }, directory)).isInstanceOf(FileInfrastructureException.class)
                .hasMessage("Private file exceeds its recorded byte count");
        assertThat(target.size()).isZero();
        assertEmptyDirectory();
    }

    @Test
    void singleByteAndOffsetWritesUseTheSameDigestAndLimit() throws Exception {
        var target = new ByteArrayOutputStream();
        VerifiedFileCopy.copy(BYTES.length, sha256(BYTES), target, staged -> {
            try {
                staged.write(BYTES[0]);
                staged.write(BYTES, 1, BYTES.length - 1);
                staged.flush();
            } catch (IOException exception) { throw new IllegalStateException(exception); }
        }, directory);
        assertThat(target.toByteArray()).isEqualTo(BYTES);
        assertEmptyDirectory();
    }

    @Test
    void failedStorageDiscardsStagedBytesAndDoesNotCloseTheCaller() throws Exception {
        var target = new ByteArrayOutputStream();
        assertThatThrownBy(() -> VerifiedFileCopy.copy(BYTES.length, sha256(BYTES), target, staged -> {
            write(staged, BYTES);
            throw new FileInfrastructureException("Synthetic storage outage");
        }, directory)).isInstanceOf(FileInfrastructureException.class).hasMessage("Synthetic storage outage");
        assertThat(target.size()).isZero();
        assertEmptyDirectory();
    }

    @Test
    void callerOutputFailureStillCleansUpThePrivateTemporaryFile() throws Exception {
        OutputStream target = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Synthetic disconnect"); }
        };
        assertThatThrownBy(() -> VerifiedFileCopy.copy(BYTES.length, sha256(BYTES), target,
                staged -> write(staged, BYTES), directory)).isInstanceOf(FileInfrastructureException.class);
        assertEmptyDirectory();
    }

    @ParameterizedTest
    @ValueSource(longs = { -1, 0, 52_428_801, Long.MAX_VALUE })
    void invalidSizeRejectsBeforeReadingStorageOrCreatingATemporaryFile(long size) throws Exception {
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> VerifiedFileCopy.copy(size, sha256(BYTES), new ByteArrayOutputStream(),
                staged -> called.set(true), directory)).isInstanceOf(FileInfrastructureException.class);
        assertThat(called).isFalse();
        assertEmptyDirectory();
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "abc", "zzzz", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" })
    void invalidDigestRejectsBeforeReadingStorage(String digest) throws Exception {
        var called = new AtomicBoolean();
        assertThatThrownBy(() -> VerifiedFileCopy.copy(BYTES.length, digest, new ByteArrayOutputStream(),
                staged -> called.set(true), directory)).isInstanceOf(FileInfrastructureException.class);
        assertThat(called).isFalse();
        assertEmptyDirectory();
    }

    @Test
    void absentDigestRejectsBeforeReadingStorage() throws Exception {
        assertThatThrownBy(() -> VerifiedFileCopy.copy(BYTES.length, null, new ByteArrayOutputStream(),
                staged -> { throw new AssertionError("Storage must not be read"); }, directory))
                .isInstanceOf(FileInfrastructureException.class);
        assertEmptyDirectory();
    }

    private void assertEmptyDirectory() throws IOException {
        try (var paths = Files.list(directory)) { assertThat(paths).isEmpty(); }
    }

    private static void write(OutputStream output, byte[] bytes) {
        try { output.write(bytes); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
