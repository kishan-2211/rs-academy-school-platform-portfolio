package com.educore.api.guardians;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class GuardianInvitationExpirySchedulerTest {
    @Test
    void boundsWorkToTwentyFiveTransactionsAndClearsSystemContext() {
        var jdbc = mock(JdbcTemplate.class);
        var invitations = mock(GuardianPortalInvitationService.class);
        UUID organisation = UUID.randomUUID();
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(organisation));
        when(invitations.expireNext(organisation)).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).hasValueSatisfying(context -> {
                assertThat(context.organisationId()).isEqualTo(organisation);
                assertThat(context.userId()).isNull();
            });
            return UUID.randomUUID();
        });
        new GuardianInvitationExpiryScheduler(jdbc, invitations).expireDueInvitations();
        verify(invitations, times(25)).expireNext(organisation);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test
    void stopsAtEmptyQueueAndRestoresPreviousContext() {
        var jdbc = mock(JdbcTemplate.class);
        var invitations = mock(GuardianPortalInvitationService.class);
        UUID organisation = UUID.randomUUID(), previousOrganisation = UUID.randomUUID(), previousUser = UUID.randomUUID();
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(organisation));
        try (var previous = TenantRequestContext.open(previousUser, previousOrganisation)) {
            new GuardianInvitationExpiryScheduler(jdbc, invitations).expireDueInvitations();
            verify(invitations).expireNext(organisation);
            assertThat(TenantRequestContext.current()).hasValueSatisfying(context -> {
                assertThat(context.userId()).isEqualTo(previousUser);
                assertThat(context.organisationId()).isEqualTo(previousOrganisation);
            });
        }
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test
    void failureStopsThatTenantAndContinuesOthersWithoutLeakingContext() {
        var jdbc = mock(JdbcTemplate.class);
        var invitations = mock(GuardianPortalInvitationService.class);
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<UUID>>any())).thenReturn(List.of(first, second));
        when(invitations.expireNext(first)).thenThrow(new IllegalStateException("Synthetic private error, not for logs"));
        when(invitations.expireNext(second)).thenAnswer(call -> {
            assertThat(TenantRequestContext.current()).hasValueSatisfying(context ->
                    assertThat(context.organisationId()).isEqualTo(second));
            return null;
        });
        new GuardianInvitationExpiryScheduler(jdbc, invitations).expireDueInvitations();
        verify(invitations).expireNext(first);
        verify(invitations).expireNext(second);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test
    void expiryDeniesMissingMismatchedOrUserContextBeforeAnyStatement() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        var invitations = new GuardianPortalInvitationService(jdbc, null, null, null, null, null, null, null, Clock.systemUTC());
        UUID organisation = UUID.randomUUID();
        assertThatThrownBy(() -> invitations.expireNext(organisation)).isInstanceOf(IllegalArgumentException.class);
        try (var ignored = TenantRequestContext.open(null, UUID.randomUUID())) {
            assertThatThrownBy(() -> invitations.expireNext(organisation)).isInstanceOf(IllegalArgumentException.class);
        }
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), organisation)) {
            assertThatThrownBy(() -> invitations.expireNext(organisation)).isInstanceOf(IllegalArgumentException.class);
        }
        try (var ignored = TenantRequestContext.open(null, organisation)) {
            assertThatThrownBy(() -> invitations.expireNext(null)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(jdbc);
    }
}
