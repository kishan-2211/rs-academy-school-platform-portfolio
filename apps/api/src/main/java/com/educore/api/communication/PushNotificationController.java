package com.educore.api.communication;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/notifications/push")
@PreAuthorize("hasAnyAuthority('CAPABILITY_communication.read','CAPABILITY_portal.guardian','CAPABILITY_portal.student')")
public class PushNotificationController {

    private final PushNotificationService push;

    public PushNotificationController(PushNotificationService push) {
        this.push = push;
    }

    @GetMapping
    PushNotificationResponse status(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam UUID schoolId) {
        return push.status(user, schoolId);
    }

    @PostMapping("/subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    PushNotificationResponse register(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RegisterPushSubscriptionRequest request) {
        return push.register(user, request);
    }

    @PostMapping("/subscriptions/revoke")
    PushNotificationResponse revoke(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RevokePushSubscriptionRequest request) {
        return push.revoke(user, request);
    }

    public record RegisterPushSubscriptionRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 4096) String endpoint,
            @NotBlank @Size(min = 80, max = 100)
            @Pattern(regexp = "^[A-Za-z0-9_-]+$") String p256dh,
            @NotBlank @Size(min = 20, max = 32)
            @Pattern(regexp = "^[A-Za-z0-9_-]+$") String auth,
            @Size(max = 80) @Pattern(regexp = "^[^\\p{Cntrl}]*$") String deviceLabel) {}

    public record RevokePushSubscriptionRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 4096) String endpoint) {}
}
