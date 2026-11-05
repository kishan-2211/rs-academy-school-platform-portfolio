package com.educore.api.files;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("privateFiles")
class FileDependenciesHealthIndicator implements HealthIndicator {

    private final ObjectStorage storage;
    private final MalwareScanner scanner;

    FileDependenciesHealthIndicator(ObjectStorage storage, MalwareScanner scanner) {
        this.storage = storage;
        this.scanner = scanner;
    }

    @Override
    public Health health() {
        try {
            storage.verifyAvailable();
            scanner.verifyAvailable();
            return Health.up().withDetail("boundary", "private-storage-and-malware-scanner").build();
        } catch (RuntimeException exception) {
            return Health.down()
                    .withDetail("boundary", "private-storage-and-malware-scanner")
                    .withDetail("reason", "required dependency unavailable")
                    .build();
        }
    }
}
