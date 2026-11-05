package com.educore.api.files;

public record FileUploadResponse(PrivateFileSummary file, long contextVersion) {}
