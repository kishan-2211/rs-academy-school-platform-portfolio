package com.educore.api.identity;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Explicit staff setup contracts. No password, raw token or destination is retained in ordinary responses. */
public final class StaffAccountInvitationModels {
    private StaffAccountInvitationModels() {}

    public record IssueRequest(@NotNull UUID requestId, @Min(0) long expectedStaffVersion,
            @NotBlank @Size(max = 80) String roleCode, @Min(1) @Max(168) int ttlHours,
            @NotBlank @Size(min = 3, max = 240) String reason) {}
    public record RevokeRequest(@NotNull UUID requestId, @Min(0) long expectedVersion,
            @NotBlank @Size(min = 3, max = 240) String reason) {}
    public record HandoffRequest(@NotNull UUID requestId, @Min(0) long expectedVersion,
            @NotBlank @Size(min = 3, max = 240) String reason) {}
    public record PreviewRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "StaffInvitationPreviewRequest[redacted]"; }
    }
    public record NewAcceptanceRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
            @NotBlank @Size(min = 16, max = 200) String password,
            @NotBlank @Size(min = 16, max = 200) String confirmation) {
        @Override public String toString() { return "StaffInvitationNewAcceptanceRequest[redacted]"; }
    }
    public record ExistingAcceptanceRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token) {
        @Override public String toString() { return "StaffInvitationExistingAcceptanceRequest[redacted]"; }
    }
    public record RoleOption(String code, String name, List<String> capabilities) {
        public RoleOption { capabilities = List.copyOf(capabilities); }
    }
    public record Invitation(UUID id, UUID schoolId, UUID staffId, String roleCode, String roleName,
            String maskedDestination, String status, String deliveryStatus, Instant expiresAt,
            Instant createdAt, Instant acceptedAt, Instant revokedAt, Instant handoffAt,
            long rowVersion, boolean canRevoke, boolean canRevealHandoff) {}
    public record ListResponse(UUID schoolId, UUID staffId, boolean manualHandoffEnabled,
            boolean canManage, List<RoleOption> roles, List<Invitation> items) {
        public ListResponse { roles = List.copyOf(roles); items = List.copyOf(items); }
    }
    public record HandoffResponse(UUID invitationId, String setupToken, Instant expiresAt) {
        @Override public String toString() { return "StaffInvitationHandoffResponse[redacted]"; }
    }
    public record PreviewResponse(String schoolName, String maskedDestination, String roleCode,
            String roleName, Instant expiresAt, boolean existingAccount, String status) {}
    public record AcceptanceResponse(String status, String nextAction) {}
}
