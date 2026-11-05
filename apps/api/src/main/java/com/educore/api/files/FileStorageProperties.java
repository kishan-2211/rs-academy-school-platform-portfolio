package com.educore.api.files;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.files")
public record FileStorageProperties(
        URI endpoint,
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        Duration connectTimeout,
        Duration readTimeout,
        boolean pathStyleAccess,
        boolean autoCreateBucket,
        long maxSizeBytes,
        Set<String> allowedMediaTypes,
        ClamAv clamav) {

    public FileStorageProperties {
        if (endpoint == null || endpoint.getScheme() == null) {
            throw new IllegalArgumentException("A valid private object-storage endpoint is required");
        }
        if (blank(region) || blank(bucket) || blank(accessKey) || blank(secretKey)) {
            throw new IllegalArgumentException("Object-storage region, bucket, and credentials are required");
        }
        if (invalid(connectTimeout, Duration.ofSeconds(30))
                || invalid(readTimeout, Duration.ofMinutes(2))) {
            throw new IllegalArgumentException("Object-storage timeouts must be positive and bounded");
        }
        if (maxSizeBytes < 1 || maxSizeBytes > 52_428_800L) {
            throw new IllegalArgumentException("Private file size must be between one byte and 50 MiB");
        }
        if (allowedMediaTypes == null || allowedMediaTypes.isEmpty()) {
            throw new IllegalArgumentException("At least one private file media type must be allowlisted");
        }
        allowedMediaTypes = allowedMediaTypes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (allowedMediaTypes.isEmpty()) {
            throw new IllegalArgumentException("At least one private file media type must be allowlisted");
        }
        if (clamav == null) throw new IllegalArgumentException("ClamAV configuration is required");
    }

    public record ClamAv(String host, int port, Duration connectTimeout, Duration readTimeout) {
        public ClamAv {
            if (blank(host) || port < 1 || port > 65_535) {
                throw new IllegalArgumentException("A valid private ClamAV host and port are required");
            }
            if (invalid(connectTimeout, Duration.ofSeconds(30))
                    || invalid(readTimeout, Duration.ofMinutes(2))) {
                throw new IllegalArgumentException("ClamAV timeouts must be positive and bounded");
            }
        }

        private static boolean invalid(Duration value, Duration maximum) {
            return value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean invalid(Duration value, Duration maximum) {
        return value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0;
    }
}
