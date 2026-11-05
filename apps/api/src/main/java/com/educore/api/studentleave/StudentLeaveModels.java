package com.educore.api.studentleave;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class StudentLeaveModels {
    private StudentLeaveModels() {}

    public record PolicyRequest(@NotNull UUID schoolId, @NotNull UUID sessionId,
            @Min(1) @Max(366) int maxCalendarDays, @Min(0) @Max(90) int retrospectiveDays,
            @NotBlank @Pattern(regexp="EXCUSED|ABSENT") String attendanceState,
            boolean enabled, @Min(0) Long expectedVersion, @NotBlank @Size(min=3,max=500) String reason) {}
    public record SubmitRequest(@NotNull UUID sessionId, @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn, @NotBlank @Pattern(regexp="PERSONAL|ILLNESS|FAMILY|OTHER") String leaveType,
            @NotBlank @Size(min=3,max=1000) String reason,
            @NotBlank @Size(min=8,max=100) String idempotencyKey) {}
    public record DecisionRequest(@NotNull UUID schoolId,
            @NotBlank @Pattern(regexp="APPROVED|REJECTED|REVOKED") String decision,
            @Min(0) long expectedVersion, @NotBlank @Size(min=3,max=1000) String reason) {}
    public record WithdrawalRequest(@Min(0) long expectedVersion,
            @NotBlank @Size(min=3,max=1000) String reason) {}
    public record Mutation(UUID id, long rowVersion, String status) {}
    public record Policy(UUID id, UUID sessionId, String sessionName, int maxCalendarDays,
            int retrospectiveDays, String attendanceState, boolean enabled, long rowVersion) {}
    public record Session(UUID id, String name, LocalDate startsOn, LocalDate endsOn) {}
    public record Request(UUID id, UUID learnerId, String learnerName, UUID sessionId,
            LocalDate startsOn, LocalDate endsOn, String leaveType, String reason, String status,
            String lastActionReason, Instant createdAt, long rowVersion, boolean canWithdraw) {}
    public record History(String previousStatus, String newStatus, String reason,
            Instant occurredAt, long requestVersion) {}
    public record Detail(Request request, List<History> history) {}
    public record Page(List<Request> requests, long total, int page, int pageSize) {}
    public record FamilyWorkspace(UUID learnerId, List<Session> sessions, List<Policy> policies, Page records) {}
    public record Workspace(UUID schoolId, String schoolName, List<Session> sessions,
            List<Policy> policies, Page records) {}
}
