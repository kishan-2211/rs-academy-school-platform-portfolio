package com.educore.api.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AccountPreferencesServiceTest {
    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private final AccountPreferencesService service = new AccountPreferencesService(users, audit);

    private static AuthenticatedUser actor(UUID id, UUID organisation) {
        return new AuthenticatedUser(id, UUID.randomUUID(), "synthetic", null, "Synthetic user", false,
                organisation, Set.of(), Set.of("portal.guardian"), List.of());
    }

    @Test void preservesUnsetForExistingAccountsAndSavesIdempotently() {
        var account = UserAccount.activeWithLoginIdentifier("synthetic-language", "Synthetic", "test-hash");
        var actor = actor(account.getId(), UUID.randomUUID());
        when(users.findById(actor.userId())).thenReturn(Optional.of(account));
        when(users.findByIdForUpdate(actor.userId())).thenReturn(Optional.of(account));
        assertThat(service.read(actor).preferredLanguage()).isNull();
        assertThat(service.update(actor, "hi").preferredLanguage()).isEqualTo("hi");
        assertThat(service.read(actor).preferredLanguage()).isEqualTo("hi");
        service.update(actor, "hi");
        verify(audit, times(1)).record(eq(actor.userId()), eq("identity.language.changed"), any(), any(), any());
        assertThat(service.update(actor, "en").preferredLanguage()).isEqualTo("en");
    }

    @Test void rejectsInvalidAndUnauthenticatedRequestsBeforeDataAccess() {
        var actor = actor(UUID.randomUUID(), UUID.randomUUID());
        for (String invalid : List.of("fr", "HI", "hi-IN", "", "hi; secure", " hi")) {
            assertThatThrownBy(() -> service.update(actor, invalid)).isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.update(actor, null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.read(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.update(null, "hi")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users, audit);
    }

    @Test void differentUsersAndSchoolsCannotChangeEachOthersPreferences() {
        var one = UserAccount.activeWithLoginIdentifier("synthetic-one", "Synthetic one", "hash");
        var two = UserAccount.activeWithLoginIdentifier("synthetic-two", "Synthetic two", "hash");
        var first = actor(one.getId(), UUID.randomUUID());
        var second = actor(two.getId(), UUID.randomUUID());
        when(users.findByIdForUpdate(one.getId())).thenReturn(Optional.of(one));
        when(users.findByIdForUpdate(two.getId())).thenReturn(Optional.of(two));
        when(users.findById(two.getId())).thenReturn(Optional.of(two));
        service.update(first, "hi");
        assertThat(service.read(second).preferredLanguage()).isNull();
        service.update(second, "en");
        assertThat(one.getPreferredLanguage()).isEqualTo("hi");
        assertThat(two.getPreferredLanguage()).isEqualTo("en");
        // Moving organisation context does not select another account.
        service.update(actor(one.getId(), second.activeOrganisationId()), "en");
        verify(users, times(2)).findByIdForUpdate(one.getId());
        verify(users, times(1)).findByIdForUpdate(two.getId());
    }

    @Test void accountEntityValidatesLanguageEvenOutsideController() {
        var account = UserAccount.activeWithLoginIdentifier("synthetic", "Synthetic", "hash");
        assertThatThrownBy(() -> account.choosePreferredLanguage("de")).isInstanceOf(IllegalArgumentException.class);
        assertThat(account.getPreferredLanguage()).isNull();
    }
}
