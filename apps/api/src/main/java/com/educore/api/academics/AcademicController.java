package com.educore.api.academics;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/academics")
public class AcademicController {

    private final AcademicService academics;

    public AcademicController(AcademicService academics) {
        this.academics = academics;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicSnapshot snapshot(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return academics.snapshot(user, schoolId);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.read')")
    TeacherAcademicSnapshot myAcademics(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return academics.teacherSnapshot(user, schoolId);
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SessionRequest request) {
        return academics.createSession(user, request);
    }

    @PutMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse updateSession(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID sessionId,
            @Valid @RequestBody SessionRequest request) {
        return academics.updateSession(user, sessionId, request);
    }

    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createTerm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TermRequest request) {
        return academics.createTerm(user, request);
    }

    @PutMapping("/terms/{termId}")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse updateTerm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID termId,
            @Valid @RequestBody TermRequest request) {
        return academics.updateTerm(user, termId, request);
    }

    @PostMapping("/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createSubject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SubjectRequest request) {
        return academics.createSubject(user, request);
    }

    @PutMapping("/subjects/{subjectId}")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse updateSubject(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID subjectId,
            @Valid @RequestBody SubjectRequest request) {
        return academics.updateSubject(user, subjectId, request);
    }

    @PostMapping("/resources")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createResource(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ResourceRequest request) {
        return academics.createResource(user, request);
    }

    @PutMapping("/resources/{resourceId}")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse updateResource(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID resourceId,
            @Valid @RequestBody ResourceRequest request) {
        return academics.updateResource(user, resourceId, request);
    }

    @PostMapping("/curriculum/publications/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createCurriculumDraft(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CurriculumDraftRequest request) {
        return academics.createCurriculumDraft(user, request);
    }

    @PostMapping("/curriculum/publications/{publicationId}/publish")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse publishCurriculum(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID publicationId,
            @Valid @RequestBody CurriculumMutationRequest request) {
        return academics.publishCurriculum(user, publicationId, request);
    }

    @PostMapping("/curriculum/publications/{publicationId}/cancel")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse cancelCurriculumDraft(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID publicationId,
            @Valid @RequestBody CurriculumMutationRequest request) {
        return academics.cancelCurriculumDraft(user, publicationId, request);
    }

    @PostMapping("/offerings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createOffering(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody OfferingRequest request) {
        return academics.createOffering(user, request);
    }

    @PutMapping("/offerings/{offeringId}")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse updateOffering(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID offeringId,
            @Valid @RequestBody OfferingRequest request) {
        return academics.updateOffering(user, offeringId, request);
    }

    @PostMapping("/allocations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createAllocation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AllocationRequest request) {
        return academics.createAllocation(user, request);
    }

    @PutMapping("/allocations/{allocationId}")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse updateAllocation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID allocationId,
            @Valid @RequestBody AllocationRequest request) {
        return academics.updateAllocation(user, allocationId, request);
    }

    @PostMapping("/periods")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createPeriod(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PeriodRequest request) {
        return academics.createPeriod(user, request);
    }

    @PutMapping("/periods/{periodId}")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse updatePeriod(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID periodId,
            @Valid @RequestBody PeriodRequest request) {
        return academics.updatePeriod(user, periodId, request);
    }

    @PostMapping("/timetables/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse createDraft(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateTimetableDraftRequest request) {
        return academics.createDraft(user, request);
    }

    @PostMapping("/timetables/{planId}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse addEntry(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID planId,
            @Valid @RequestBody TimetableEntryRequest request) {
        return academics.addEntry(user, planId, request);
    }

    @PostMapping("/timetables/{planId}/entries/{entryId}/remove")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse removeEntry(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID planId,
            @PathVariable UUID entryId,
            @Valid @RequestBody DraftMutationRequest request) {
        return academics.removeEntry(user, planId, entryId, request);
    }

    @PostMapping("/timetables/{planId}/publish")
    @PreAuthorize("hasAuthority('CAPABILITY_academics.configure')")
    AcademicMutationResponse publish(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID planId,
            @Valid @RequestBody DraftMutationRequest request) {
        return academics.publish(user, planId, request);
    }

    public record SessionRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 80) String name,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            @NotBlank @Size(max = 20) String status,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason,
            @Min(0) @Max(1000) Integer rteSeatLimit,
            @Min(0) @Max(18) Integer rteMinAgeYears,
            @Min(0) @Max(18) Integer rteMaxAgeYears) {
        public SessionRequest(UUID schoolId, String name, LocalDate startsOn, LocalDate endsOn,
                String status, Long expectedVersion, String reason) {
            this(schoolId, name, startsOn, endsOn, status, expectedVersion, reason, null, null, null);
        }
    }

    public record TermRequest(
            @NotNull UUID schoolId,
            @NotNull UUID academicSessionId,
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 100) String name,
            @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn,
            @NotBlank @Size(max = 20) String status,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record SubjectRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 40) String shortName,
            @NotBlank @Size(max = 20) String category,
            @Size(max = 20) String languageCode,
            @Size(max = 30) String boardSubjectCode,
            @NotBlank @Size(max = 20) String status,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record ResourceRequest(
            @NotNull UUID schoolId,
            @NotNull UUID academicSessionId,
            @NotNull UUID subjectId,
            @NotBlank @Size(max = 40) String gradeCode,
            @NotBlank @Size(max = 20) String resourceType,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 1000) String description,
            @Size(max = 160) String author,
            @Size(max = 160) String publisher,
            @Size(max = 80) String edition,
            @Size(max = 32) String isbn,
            @Size(max = 1000) String sourceUrl,
            @Min(0) @Max(1000) int displayOrder,
            @NotBlank @Size(max = 20) String status,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record CurriculumDraftRequest(
            @NotNull UUID schoolId,
            @NotNull UUID academicSessionId,
            @NotBlank @Size(max = 40) String gradeCode,
            @NotBlank @Size(max = 240) String reason) {}

    public record CurriculumMutationRequest(
            @NotNull UUID schoolId,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 240) String reason) {}

    public record OfferingRequest(
            @NotNull UUID schoolId,
            @NotNull UUID sectionId,
            @NotNull UUID subjectId,
            @Min(0) @Max(60) int weeklyTargetPeriods,
            @NotBlank @Size(max = 20) String status,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record AllocationRequest(
            @NotNull UUID schoolId,
            @NotNull UUID offeringId,
            @NotNull UUID teacherMembershipId,
            @NotBlank @Size(max = 20) String allocationRole,
            @NotNull LocalDate startsOn,
            LocalDate endsOn,
            @NotBlank @Size(max = 20) String status,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record PeriodRequest(
            @NotNull UUID schoolId,
            @NotNull UUID campusId,
            @NotNull UUID academicSessionId,
            @Min(1) @Max(7) int dayOfWeek,
            @Min(1) @Max(40) int sequenceNumber,
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 80) String name,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt,
            @NotBlank @Size(max = 20) String kind,
            @NotBlank @Size(max = 20) String status,
            @Min(0) Long expectedVersion,
            @Size(max = 240) String reason) {}

    public record CreateTimetableDraftRequest(
            @NotNull UUID schoolId,
            @NotNull UUID campusId,
            @NotNull UUID academicSessionId,
            UUID academicTermId,
            @NotBlank @Size(max = 120) String name,
            boolean copyPublished,
            @Size(max = 240) String reason) {}

    public record TimetableEntryRequest(
            @NotNull UUID schoolId,
            @NotNull UUID sectionId,
            @NotNull UUID offeringId,
            @NotNull UUID periodId,
            @NotNull UUID teacherMembershipId,
            @Size(max = 80) String roomLabel,
            @Size(max = 240) String note,
            @Min(0) long expectedPlanVersion,
            @Size(max = 240) String reason) {}

    public record DraftMutationRequest(
            @NotNull UUID schoolId,
            @Min(0) long expectedPlanVersion,
            @NotBlank @Size(max = 240) String reason) {}
}
