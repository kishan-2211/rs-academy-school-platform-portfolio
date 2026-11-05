package com.educore.api.tenancy;

import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SessionService;
import com.educore.api.identity.PrivilegedMfaPolicy;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/context")
public class ContextController {

    private final SessionService sessions;
    private final PrivilegedMfaPolicy mfaPolicy;

    public ContextController(SessionService sessions, PrivilegedMfaPolicy mfaPolicy) {
        this.sessions = sessions;
        this.mfaPolicy = mfaPolicy;
    }

    @GetMapping
    CurrentContextResponse current(@AuthenticationPrincipal AuthenticatedUser user) {
        return CurrentContextResponse.from(user, mfaPolicy.isRequired(user));
    }

    @PutMapping("/organisation")
    @SuppressWarnings("try")
    CurrentContextResponse selectOrganisation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SelectOrganisationRequest request) {
        AuthenticatedUser selected = sessions.selectOrganisation(user, request.organisationId());
        // The response policy query must acquire its connection in the newly selected RLS scope.
        try (var ignored = TenantRequestContext.open(selected.userId(), selected.activeOrganisationId())) {
            return CurrentContextResponse.from(selected, mfaPolicy.isRequired(selected));
        }
    }
}
