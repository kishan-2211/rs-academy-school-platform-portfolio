package com.educore.api.config;

import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.files.FileStorageProperties;
import java.net.URI;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProductionSecurityValidator implements ApplicationRunner {

    private final ApplicationProperties properties;
    private final FileStorageProperties fileProperties;
    private final Environment environment;

    public ProductionSecurityValidator(
            ApplicationProperties properties,
            FileStorageProperties fileProperties,
            Environment environment) {
        this.properties = properties;
        this.fileProperties = fileProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!environment.matchesProfiles("prod")) return;
        if (!SensitiveValueCipher.isValidKey(properties.security().encryptionKey())) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY must be a base64-encoded 32-byte key in production");
        }
        URI publicUrl = URI.create(properties.publicUrl());
        if (!"https".equalsIgnoreCase(publicUrl.getScheme())) {
            throw new IllegalStateException("APP_PUBLIC_URL must use HTTPS in production");
        }
        List<String> insecureOrigins = properties.allowedOrigins().stream()
                .filter(origin -> !origin.startsWith("https://"))
                .toList();
        if (!insecureOrigins.isEmpty()) {
            throw new IllegalStateException("Every APP_ALLOWED_ORIGINS entry must use HTTPS in production");
        }
        if (!properties.security().cookieSecure()) {
            throw new IllegalStateException("Secure authentication cookies are mandatory in production");
        }
        if (properties.publicOrganisationId().isEmpty() || properties.publicSchoolId().isEmpty()) {
            throw new IllegalStateException("APP_PUBLIC_ORGANISATION_ID and APP_PUBLIC_SCHOOL_ID are mandatory in production");
        }
        if (fileProperties.autoCreateBucket()) {
            throw new IllegalStateException("S3_AUTO_CREATE_BUCKET must be false in production; provision the private bucket separately");
        }
        if (fileProperties.secretKey().equals("change-me-locally")
                || fileProperties.accessKey().equals("educore-local")) {
            throw new IllegalStateException("Dedicated private object-storage credentials are mandatory in production");
        }
        if (fileProperties.clamav().host().equalsIgnoreCase("localhost")) {
            throw new IllegalStateException("CLAMAV_HOST must reference the private scanner service in production");
        }
    }
}
