package com.educore.api.payroll;

import com.educore.api.files.FileStorageProperties;
import com.educore.api.files.ObjectStorage;
import java.io.OutputStream;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

@Component
final class PrivateBucketPayrollPayslipStore implements PayrollPayslipObjectStore {
    private final ObjectStorage storage;
    private final FileStorageProperties properties;

    PrivateBucketPayrollPayslipStore(ObjectStorage storage, FileStorageProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public String bucket() {
        return properties.bucket();
    }

    @Override
    public void put(String objectKey, Path source, long contentLength, String sha256Hex) {
        storage.put(objectKey, source, "application/pdf", contentLength, sha256Hex);
    }

    @Override
    public void copyTo(String objectKey, OutputStream target) {
        storage.copyTo(objectKey, target);
    }

    @Override
    public void delete(String objectKey) {
        storage.delete(objectKey);
    }
}
