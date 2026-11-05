package com.educore.api.identity;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountPreferencesService {
    private final UserAccountRepository users;
    private final SecurityAuditService audit;

    public AccountPreferencesService(UserAccountRepository users, SecurityAuditService audit) {
        this.users = users;
        this.audit = audit;
    }

    // Account-wide, not school-wide. The target is always the authenticated user,
    // never a caller-supplied user, membership or organisation identifier.
    @Transactional(readOnly = true)
    public Preferences read(AuthenticatedUser actor) {
        requireActor(actor);
        return new Preferences(users.findById(actor.userId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)).getPreferredLanguage());
    }

    @Transactional
    public Preferences update(AuthenticatedUser actor, String language) {
        requireActor(actor);
        if (!"en".equals(language) && !"hi".equals(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported language");
        }
        var account = users.findByIdForUpdate(actor.userId()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!Objects.equals(account.getPreferredLanguage(), language)) {
            account.choosePreferredLanguage(language);
            audit.record(actor.userId(), "identity.language.changed", SecurityAuditService.AuditOutcome.SUCCEEDED,
                    "Account language selected", Map.of("language", language));
        }
        return new Preferences(account.getPreferredLanguage());
    }

    private static void requireActor(AuthenticatedUser actor) {
        if (actor == null || actor.userId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    public record Preferences(String preferredLanguage) {}
}
