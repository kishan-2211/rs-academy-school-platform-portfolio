package com.educore.api.family;

import com.educore.api.communication.CommunicationInboxResponse;
import com.educore.api.communication.CommunicationInboxService;
import com.educore.api.communication.CommunicationMutationResponse;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.finance.FamilyFeeAccountsResponse;
import com.educore.api.finance.FamilyFeesResponse;
import com.educore.api.finance.FamilyFinanceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/family")
public class FamilyController {

    private final FamilyService family;
    private final FamilyAttendanceService attendance;
    private final FamilyResultService results;
    private final FamilyLearningService learning;
    private final FamilyFinanceService finance;
    private final CommunicationInboxService communications;

    public FamilyController(
            FamilyService family,
            FamilyAttendanceService attendance,
            FamilyResultService results,
            FamilyLearningService learning,
            FamilyFinanceService finance,
            CommunicationInboxService communications) {
        this.family = family;
        this.attendance = attendance;
        this.results = results;
        this.learning = learning;
        this.finance = finance;
        this.communications = communications;
    }

    @GetMapping("/children")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FamilyChildrenResponse children(@AuthenticationPrincipal AuthenticatedUser user) {
        return family.children(user);
    }

    @GetMapping("/children/{learnerId}/schedule")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FamilyScheduleResponse schedule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return family.schedule(user, learnerId);
    }

    @GetMapping("/children/{learnerId}/curriculum")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    com.educore.api.academics.PublishedCurriculumResponse curriculum(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return family.curriculum(user, learnerId);
    }

    @GetMapping("/children/{learnerId}/calendar")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    com.educore.api.schoolcalendar.PublishedSchoolCalendarResponse calendar(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return family.calendar(user, learnerId);
    }

    @GetMapping("/children/{learnerId}/attendance")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FamilyAttendanceResponse attendance(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return attendance.attendance(user, learnerId, from, to);
    }

    @GetMapping("/children/{learnerId}/results")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FamilyResultsResponse results(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return results.results(user, learnerId);
    }

    @GetMapping("/children/{learnerId}/assignments")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FamilyAssignmentsResponse assignments(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return learning.assignments(user, learnerId);
    }

    @GetMapping("/children/{learnerId}/fees")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    ResponseEntity<FamilyFeesResponse> fees(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @RequestParam(required = false) UUID accountId) {
        return privateFinanceResponse(finance.fees(user, learnerId, accountId));
    }

    @GetMapping("/children/{learnerId}/fee-accounts")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    ResponseEntity<FamilyFeeAccountsResponse> feeAccounts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int pageSize) {
        return privateFinanceResponse(finance.feeAccounts(user, learnerId, page, pageSize));
    }

    @GetMapping("/children/{learnerId}/communications")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    CommunicationInboxResponse communications(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return communications.familyInbox(user, learnerId);
    }

    @PostMapping("/children/{learnerId}/communications/{recipientId}/read")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    CommunicationMutationResponse readCommunication(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID recipientId) {
        return communications.readFamily(user, learnerId, recipientId);
    }

    @PostMapping("/children/{learnerId}/communications/{recipientId}/acknowledge")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    CommunicationMutationResponse acknowledgeCommunication(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID recipientId) {
        return communications.acknowledgeFamily(user, learnerId, recipientId);
    }

    @PostMapping("/children/{learnerId}/assignments/{assignmentId}/submission")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    com.educore.api.learning.LearningMutationResponse saveAssignmentSubmission(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody AssignmentSubmissionRequest request) {
        return learning.saveSubmission(user, learnerId, assignmentId, request.action(),
                request.responseText(), request.responseLink(), request.expectedVersion());
    }

    @PostMapping("/children/{learnerId}/attendance/{markId}/explanations")
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FamilyExplanationResponse explainAttendance(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID markId,
            @Valid @RequestBody AttendanceExplanationRequest request) {
        return attendance.explain(user, learnerId, markId, request.explanation());
    }

    public record AttendanceExplanationRequest(
            @NotBlank @Size(min = 3, max = 1000) String explanation) {}

    public record AssignmentSubmissionRequest(
            @NotBlank String action,
            @Size(max = 8000) String responseText,
            @Size(max = 1000) String responseLink,
            @Min(0) long expectedVersion) {}

    private static <T> ResponseEntity<T> privateFinanceResponse(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.VARY, HttpHeaders.COOKIE).header("X-Content-Type-Options", "nosniff").body(body);
    }
}
