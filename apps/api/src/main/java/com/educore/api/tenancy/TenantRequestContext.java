package com.educore.api.tenancy;

import java.util.Optional;
import java.util.UUID;

public final class TenantRequestContext {

    private static final ThreadLocal<ScopeValue> CURRENT = new ThreadLocal<>();

    private TenantRequestContext() {
    }

    public static Scope open(UUID userId, UUID organisationId) {
        ScopeValue previous = CURRENT.get();
        CURRENT.set(new ScopeValue(userId, organisationId));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Optional<ScopeValue> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public record ScopeValue(UUID userId, UUID organisationId) {
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
