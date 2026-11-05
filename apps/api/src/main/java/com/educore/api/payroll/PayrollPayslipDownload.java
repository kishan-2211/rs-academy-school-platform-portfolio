package com.educore.api.payroll;

public record PayrollPayslipDownload(
        String filename, long sizeBytes, PayrollPayslipObjectStoreWriter writer) {
    @FunctionalInterface
    public interface PayrollPayslipObjectStoreWriter {
        void writeTo(java.io.OutputStream output);
    }
}
