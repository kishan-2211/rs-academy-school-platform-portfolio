package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class PrivilegedMfaPolicyTest {
    private final MockEnvironment environment = new MockEnvironment();
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private final PrivilegedMfaPolicy policy = new PrivilegedMfaPolicy(environment, jdbc, audit, Clock.systemUTC());

    @Test void onlyProductionUnverifiedSelectedContextsAreChecked() {
        assertThat(policy.isRequired(user(false, UUID.randomUUID()))).isFalse();
        environment.setActiveProfiles("prod");
        assertThat(policy.isRequired(user(true, UUID.randomUUID()))).isFalse();
        assertThat(policy.isRequired(user(false, null))).isFalse();
        verifyNoInteractions(jdbc, audit);
    }

    @Test void liveGrantsAreNotCachedAndUnexpectedNullFailsClosed() {
        environment.setActiveProfiles("prod");
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Boolean.class))).thenReturn(false, true, false, null);
        AuthenticatedUser user = user(false, UUID.randomUUID());
        assertThat(policy.isRequired(user)).isFalse();
        assertThat(policy.isRequired(user)).isTrue();
        assertThat(policy.isRequired(user)).isFalse();
        assertThat(policy.isRequired(user)).isTrue();
    }

    @Test void queryBindsOnlyCurrentUserAndOrganisationWithLiveRestrictedValidityRules() {
        environment.setActiveProfiles("prod");
        AuthenticatedUser user = user(false, UUID.randomUUID());
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Boolean.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            java.util.Map<?, ?> parameters = call.getArgument(1);
            assertThat(parameters.get("user")).isEqualTo(user.userId());
            assertThat(parameters.get("organisation")).isEqualTo(user.activeOrganisationId());
            assertThat(parameters.keySet()).hasSize(3);
            assertThat(sql).contains("m.user_id = :user", "m.organisation_id = :organisation", "m.status = 'ACTIVE'",
                    "m.valid_from <= :now", "m.valid_until > :now", "r.organisation_id = :organisation", "c.risk_level = 'RESTRICTED'");
            return true;
        });
        assertThat(policy.isRequired(user)).isTrue();
    }

    @Test void selfServiceAllowlistIsExactAndMethodSpecific() {
        String id = UUID.randomUUID().toString();
        for (String path : List.of("/api/v1/context", "/api/v1/auth/sessions/current", "/api/v1/auth/sessions", "/api/v1/auth/mfa", "/api/v1/me/preferences")) {
            assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("GET", path)).isTrue();
            assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("PATCH", path)).isFalse();
            assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("GET", path + "/anything")).isFalse();
        }
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("PUT", "/api/v1/context/organisation")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("PUT", "/api/v1/me/preferences")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("DELETE", "/api/v1/auth/sessions/" + id)).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("DELETE", "/api/v1/auth/sessions/current")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("DELETE", "/api/v1/auth/sessions/not-an-id")).isFalse();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("POST", "/api/v1/auth/mfa/totp/enrollments")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("POST", "/api/v1/auth/mfa/totp/enrollments/" + id + "/verify")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("GET", "/api/v1/auth/mfa/totp/enrollments")).isFalse();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("GET", "/api/v1/workspace")).isFalse();
    }

    @Test void publicAndLoginRoutesKeepExistingBoundariesWithoutBroadAuthExemption() {
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("GET", "/api/v1/public/school")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("POST", "/api/v1/public/admission-enquiries")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("POST", "/api/v1/auth/sessions")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("POST", "/api/v1/auth/mfa/challenges/verify")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("GET", "/actuator/health/readiness")).isTrue();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("POST", "/api/v1/auth/invitations")).isFalse();
        assertThat(PrivilegedMfaPolicy.allowsWithoutVerification("GET", "/api/v1/context/organisation")).isFalse();
    }

    private AuthenticatedUser user(boolean verified, UUID organisation) {
        return new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic-mfa@example.test", null,
                "Synthetic MFA", verified, organisation, Set.of(), Set.of(), List.of());
    }
}
