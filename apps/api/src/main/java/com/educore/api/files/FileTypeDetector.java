package com.educore.api.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

final class FileTypeDetector {

    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
    private static final Map<String, String> EXTENSIONS = Map.of(
            "application/pdf", ".pdf",
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");

    String detect(Path file) {
        byte[] header = new byte[16];
        int length;
        try (InputStream input = Files.newInputStream(file)) {
            length = input.read(header);
        } catch (IOException exception) {
            throw new FileInfrastructureException("The quarantined file could not be inspected", exception);
        }
        if (startsWith(header, length, PDF)) return "application/pdf";
        if (startsWith(header, length, JPEG)) return "image/jpeg";
        if (startsWith(header, length, PNG)) return "image/png";
        if (length >= 12
                && ascii(header, 0, 4).equals("RIFF")
                && ascii(header, 8, 4).equals("WEBP")) return "image/webp";
        return null;
    }

    String extension(String mediaType) {
        return EXTENSIONS.get(mediaType.toLowerCase(Locale.ROOT));
    }

    private static boolean startsWith(byte[] value, int length, byte[] prefix) {
        return length >= prefix.length && Arrays.equals(value, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static String ascii(byte[] value, int offset, int length) {
        return new String(value, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
