package com.educore.api.studentportal;

import com.educore.api.communication.CommunicationInboxResponse;
import com.educore.api.communication.CommunicationInboxService;
import com.educore.api.communication.CommunicationMutationResponse;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.learning.LearningMutationResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
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
@RequestMapping("/api/v1/student")
@PreAuthorize("hasAuthority('CAPABILITY_portal.student')")
public class StudentPortalController {

    private final StudentPortalService portal;
    private final CommunicationInboxService communications;

    public StudentPortalController(StudentPortalService portal, CommunicationInboxService communications) {
        this.portal = portal;
        this.communications = communications;
    }

    @GetMapping("/me")
    StudentPortalResponses.Overview overview(@AuthenticationPrincipal AuthenticatedUser user) {
        return portal.overview(user);
    }

    @GetMapping("/schedule")
    StudentPortalResponses.Schedule schedule(@AuthenticationPrincipal AuthenticatedUser user) {
        return portal.schedule(user);
    }

    @GetMapping("/curriculum")
    com.educore.api.academics.PublishedCurriculumResponse curriculum(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return portal.curriculum(user);
    }

    @GetMapping("/calendar")
    com.educore.api.schoolcalendar.PublishedSchoolCalendarResponse calendar(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return portal.calendar(user);
    }

    @GetMapping("/attendance")
    StudentPortalResponses.Attendance attendance(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return portal.attendance(user, from, to);
    }

    @GetMapping("/assignments")
    StudentPortalResponses.Assignments assignments(@AuthenticationPrincipal AuthenticatedUser user) {
        return portal.assignments(user);
    }

    @GetMapping("/results")
    StudentPortalResponses.Results results(@AuthenticationPrincipal AuthenticatedUser user) {
        return portal.results(user);
    }

    @GetMapping("/communications")
    CommunicationInboxResponse communications(@AuthenticationPrincipal AuthenticatedUser user) {
        return communications.studentInbox(user);
    }

    @PostMapping("/communications/{recipientId}/read")
    CommunicationMutationResponse readCommunication(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID recipientId) {
        return communications.readStudent(user, recipientId);
    }

    @PostMapping("/communications/{recipientId}/acknowledge")
    CommunicationMutationResponse acknowledgeCommunication(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID recipientId) {
        return communications.acknowledgeStudent(user, recipientId);
    }

    @PostMapping("/assignments/{assignmentId}/submission")
    LearningMutationResponse saveSubmission(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID assignmentId,
            @Valid @RequestBody SubmissionRequest request) {
        return portal.saveSubmission(user, assignmentId, request.action(), request.responseText(),
                request.responseLink(), request.expectedVersion());
    }

    public record SubmissionRequest(
            @NotBlank @Pattern(regexp = "DRAFT|SUBMIT") String action,
            @Size(max = 8000) String responseText,
            @Size(max = 1000) String responseLink,
            @Min(0) long expectedVersion) {}
}
