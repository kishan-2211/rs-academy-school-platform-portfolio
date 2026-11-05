package com.educore.api.files;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/** Verify the recorded bytes before releasing any private content to a caller. */
public final class VerifiedFileCopy {
    // Matches the existing maximum private-file configuration, not a heap allocation.
    private static final long MAX_SIZE_BYTES = 52_428_800L;

    private VerifiedFileCopy() {}

    public static void copy(long expectedSize, String expectedSha256, OutputStream target,
                            Consumer<OutputStream> source) {
        copy(expectedSize, expectedSha256, target, source, null);
    }

    static void copy(long expectedSize, String expectedSha256, OutputStream target,
                     Consumer<OutputStream> source, Path temporaryDirectory) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(source);
        if (expectedSize < 1 || expectedSize > MAX_SIZE_BYTES || expectedSha256 == null
                || !expectedSha256.matches("[a-f0-9]{64}")) {
            throw new FileInfrastructureException("Private file integrity metadata is invalid");
        }
        Path staged = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Path directory = temporaryDirectory == null
                    ? Path.of(System.getProperty("java.io.tmpdir")) : temporaryDirectory;
            // Apply owner-only POSIX permissions atomically, not after private
            // bytes are written. Other hosts require a service-private temp ACL.
            FileAttribute<?>[] permissions = Files.getFileStore(directory).supportsFileAttributeView("posix")
                    ? new FileAttribute<?>[] { PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")) }
                    : new FileAttribute<?>[0];
            staged = Files.createTempFile(directory, "educore-verified-", ".download", permissions);
            long received;
            try (var file = Files.newOutputStream(staged);
                 var checked = new BoundedDigestOutputStream(file, digest, expectedSize)) {
                source.accept(checked);
                received = checked.received;
            }
            if (received != expectedSize || !MessageDigest.isEqual(digest.digest(),
                    HexFormat.of().parseHex(expectedSha256))) {
                throw new FileInfrastructureException("Private file integrity verification failed");
            }
            // The original caller owns its output; never close it or send staged
            // bytes until both the database digest and exact byte count match.
            Files.copy(staged, target);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new FileInfrastructureException("Private file verification could not complete", exception);
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException exception) {
                    throw new FileInfrastructureException("Private verified-file cleanup failed", exception);
                }
            }
        }
    }

    private static final class BoundedDigestOutputStream extends OutputStream {
        private final OutputStream target;
        private final MessageDigest digest;
        private final long maximum;
        private long received;

        private BoundedDigestOutputStream(OutputStream target, MessageDigest digest, long maximum) {
            this.target = target;
            this.digest = digest;
            this.maximum = maximum;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
            target.write(value);
            digest.update((byte) value);
            received++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            reserve(length);
            target.write(bytes, offset, length);
            digest.update(bytes, offset, length);
            received += length;
        }

        private void reserve(int length) {
            if (length > maximum - received) {
                throw new FileInfrastructureException("Private file exceeds its recorded byte count");
            }
        }

        @Override
        public void flush() throws IOException { target.flush(); }
    }
}
