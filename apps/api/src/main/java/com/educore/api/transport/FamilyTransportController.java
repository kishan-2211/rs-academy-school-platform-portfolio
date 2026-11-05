package com.educore.api.transport;

import com.educore.api.identity.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/family/children")
public class FamilyTransportController {
    private final FamilyTransportService transport;

    public FamilyTransportController(FamilyTransportService transport) { this.transport = transport; }

    @GetMapping("/{learnerId}/transport")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FamilyTransportResponse transport(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return transport.transport(user, learnerId);
    }
}
