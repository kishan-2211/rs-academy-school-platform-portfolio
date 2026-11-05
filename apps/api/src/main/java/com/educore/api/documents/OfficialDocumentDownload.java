package com.educore.api.documents;

public record OfficialDocumentDownload(
        String filename,
        long sizeBytes,
        ContentWriter writer) {
    @FunctionalInterface
    public interface ContentWriter {
        void writeTo(java.io.OutputStream output);
    }
}
