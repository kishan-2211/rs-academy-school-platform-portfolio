package com.educore.api.communication;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/communications/push-operations")
public class PushOperationsController {
    private final PushOperationsService operations;
    public PushOperationsController(PushOperationsService operations) { this.operations=operations; }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('CAPABILITY_communication.configure','CAPABILITY_communication.approve')")
    ResponseEntity<PushOperationsResponse> snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam UUID schoolId) {
        return privateResponse(operations.snapshot(user,schoolId));
    }

    @PutMapping("/provider")
    @PreAuthorize("hasAuthority('CAPABILITY_communication.approve')")
    ResponseEntity<PushOperationsResponse> changeProvider(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ProviderChange request) {
        return privateResponse(operations.changeProvider(user,request));
    }

    private static ResponseEntity<PushOperationsResponse> privateResponse(PushOperationsResponse response) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(response);
    }
    public record ProviderChange(@NotNull UUID schoolId,
            @NotNull @Pattern(regexp="ACTIVE|SUSPENDED") String status,
            @Min(0) Long expectedVersion, @NotBlank @Size(min=3,max=500) String reason) {}
}
