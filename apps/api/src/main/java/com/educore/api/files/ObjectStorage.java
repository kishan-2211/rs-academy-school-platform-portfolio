package com.educore.api.files;

import java.io.OutputStream;
import java.nio.file.Path;

public interface ObjectStorage {
    void put(String objectKey, Path source, String mediaType, long contentLength, String sha256Hex);
    void copyTo(String objectKey, OutputStream target);
    default void copyVerifiedTo(String objectKey, long sizeBytes, String sha256Hex, OutputStream target) {
        VerifiedFileCopy.copy(sizeBytes, sha256Hex, target, staged -> copyTo(objectKey, staged));
    }
    void delete(String objectKey);
    default void verifyAvailable() {}
}
