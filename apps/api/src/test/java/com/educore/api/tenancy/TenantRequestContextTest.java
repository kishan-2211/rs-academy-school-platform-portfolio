package com.educore.api.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantRequestContextTest {

    @Test
    @SuppressWarnings("try")
    void restoresNestedScopeAndClearsTheThreadAfterTheRequest() {
        UUID firstUser = UUID.randomUUID();
        UUID firstOrganisation = UUID.randomUUID();
        UUID secondUser = UUID.randomUUID();

        assertThat(TenantRequestContext.current()).isEmpty();
        try (TenantRequestContext.Scope outer = TenantRequestContext.open(firstUser, firstOrganisation)) {
            assertThat(TenantRequestContext.current()).contains(
                    new TenantRequestContext.ScopeValue(firstUser, firstOrganisation));
            try (TenantRequestContext.Scope inner = TenantRequestContext.open(secondUser, null)) {
                assertThat(TenantRequestContext.current()).contains(
                        new TenantRequestContext.ScopeValue(secondUser, null));
            }
            assertThat(TenantRequestContext.current()).contains(
                    new TenantRequestContext.ScopeValue(firstUser, firstOrganisation));
        }
        assertThat(TenantRequestContext.current()).isEmpty();
    }
}
