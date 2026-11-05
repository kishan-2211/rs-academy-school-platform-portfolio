package com.educore.api.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/preferences")
public class AccountPreferencesController {
    private final AccountPreferencesService preferences;

    public AccountPreferencesController(AccountPreferencesService preferences) {
        this.preferences = preferences;
    }

    @GetMapping
    public ResponseEntity<AccountPreferencesService.Preferences> read(
            @AuthenticationPrincipal AuthenticatedUser actor, CsrfToken csrf) {
        csrf.getToken();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(preferences.read(actor));
    }

    @PutMapping
    public ResponseEntity<AccountPreferencesService.Preferences> update(
            @AuthenticationPrincipal AuthenticatedUser actor, @Valid @RequestBody LanguageRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(preferences.update(actor, request.preferredLanguage()));
    }

    public record LanguageRequest(@NotNull @Pattern(regexp = "en|hi") String preferredLanguage) {}
}
