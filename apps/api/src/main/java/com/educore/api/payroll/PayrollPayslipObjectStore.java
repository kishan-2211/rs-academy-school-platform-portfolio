package com.educore.api.payroll;

import java.io.OutputStream;
import java.nio.file.Path;

public interface PayrollPayslipObjectStore {
    String bucket();
    void put(String objectKey, Path source, long contentLength, String sha256Hex);
    void copyTo(String objectKey, OutputStream target);
    default void copyVerifiedTo(String objectKey, long sizeBytes, String sha256Hex, OutputStream target) {
        com.educore.api.files.VerifiedFileCopy.copy(sizeBytes, sha256Hex, target,
                staged -> copyTo(objectKey, staged));
    }
    void delete(String objectKey);
}
