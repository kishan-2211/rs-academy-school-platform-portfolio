package com.educore.api.files;

import java.io.OutputStream;

public record PrivateFileDownload(
        String filename,
        String mediaType,
        long sizeBytes,
        Writer writer) {

    @FunctionalInterface
    public interface Writer {
        void writeTo(OutputStream output);
    }
}
