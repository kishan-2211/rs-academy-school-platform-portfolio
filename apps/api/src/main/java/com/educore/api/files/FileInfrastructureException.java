package com.educore.api.files;

public class FileInfrastructureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FileInfrastructureException(String message, Throwable cause) {
        super(message, cause);
    }

    public FileInfrastructureException(String message) {
        super(message);
    }
}
