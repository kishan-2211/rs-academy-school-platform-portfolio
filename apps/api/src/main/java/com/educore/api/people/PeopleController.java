package com.educore.api.people;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/people")
public class PeopleController {
    private final PeopleService people;

    public PeopleController(PeopleService people) { this.people = people; }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_people.read')")
    PeopleSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return people.snapshot(user, schoolId);
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse createDepartment(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DepartmentRequest request) { return people.createDepartment(user, request); }

    @PutMapping("/departments/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse updateDepartment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DepartmentRequest request) {
        return people.updateDepartment(user, id, request);
    }

    @PostMapping("/designations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse createDesignation(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DesignationRequest request) { return people.createDesignation(user, request); }

    @PutMapping("/designations/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse updateDesignation(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DesignationRequest request) {
        return people.updateDesignation(user, id, request);
    }

    @PostMapping("/staff")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse createStaff(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody StaffRequest request) { return people.createStaff(user, request); }

    @PutMapping("/staff/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse updateStaff(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody StaffRequest request) {
        return people.updateStaff(user, id, request);
    }

    @PostMapping("/staff/{id}/end")
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse endStaff(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody EndRequest request) {
        return people.endStaff(user, id, request);
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse createAssignment(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AssignmentRequest request) { return people.createAssignment(user, request); }

    @PostMapping("/assignments/{id}/end")
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse endAssignment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody EndRequest request) {
        return people.endAssignment(user, id, request);
    }

    @PostMapping("/qualifications")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse createQualification(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody QualificationRequest request) { return people.createQualification(user, request); }

    @PostMapping("/qualifications/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_people.compliance')")
    PeopleMutationResponse decideQualification(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return people.decideQualification(user, id, request);
    }

    @PostMapping("/compliance-types")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse createComplianceType(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ComplianceTypeRequest request) { return people.createComplianceType(user, request); }

    @PutMapping("/compliance-types/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse updateComplianceType(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ComplianceTypeRequest request) {
        return people.updateComplianceType(user, id, request);
    }

    @PostMapping("/compliance-checks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_people.compliance')")
    PeopleMutationResponse createComplianceCheck(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ComplianceCheckRequest request) { return people.createComplianceCheck(user, request); }

    @PostMapping("/compliance-checks/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_people.compliance')")
    PeopleMutationResponse decideComplianceCheck(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ComplianceDecisionRequest request) {
        return people.decideComplianceCheck(user, id, request);
    }

    @PostMapping("/public-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse createPublicProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PublicProfileRequest request) { return people.createPublicProfile(user, request); }

    @PutMapping("/public-profiles/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse updatePublicProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody PublicProfileRequest request) {
        return people.updatePublicProfile(user, id, request);
    }

    @PostMapping("/public-profiles/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_people.manage')")
    PeopleMutationResponse submitPublicProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReason request) {
        return people.submitPublicProfile(user, id, request);
    }

    @PostMapping("/public-profiles/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_people.publish')")
    PeopleMutationResponse decidePublicProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return people.decidePublicProfile(user, id, request);
    }

    public record DepartmentRequest(@NotNull UUID schoolId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_ ]{1,39}$") String code,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String description, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record DesignationRequest(@NotNull UUID schoolId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_ ]{1,39}$") String code,
            @NotBlank @Size(max = 120) String name,
            @NotBlank String staffCategory, String cbseStaffCategory,
            boolean teachingRole, boolean leadershipRole,
            boolean eligibleAsTransportDriver, boolean eligibleAsTransportAttendant,
            @Size(max = 500) String description,
            @Min(0) @Max(500) int displayOrder, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record StaffRequest(@NotNull UUID schoolId,
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_/-]{1,49}$") String employeeCode,
            @NotBlank @Size(max = 160) String legalName,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank String primaryLanguage, @Email @Size(max = 254) String workEmail,
            @Size(max = 30) String workPhone, UUID userAccountId, @NotNull LocalDate joinedOn,
            @NotBlank String status, @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record AssignmentRequest(@NotNull UUID schoolId, @NotNull UUID staffProfileId,
            @NotNull UUID designationId, UUID departmentId, @NotNull UUID campusId,
            @NotBlank String employmentType, @NotBlank String employerKind,
            @Size(max = 160) String agencyName, boolean primaryAssignment,
            @NotNull LocalDate startsOn, @NotBlank String status,
            @NotBlank @Size(max = 500) String reason) {}

    public record QualificationRequest(@NotNull UUID schoolId, @NotNull UUID staffProfileId,
            @NotBlank @Size(max = 180) String name, @NotBlank String level,
            @Size(max = 160) String subjectSpecialisation,
            @Size(max = 200) String institutionName, @Size(max = 200) String awardingBody,
            @Min(1950) @Max(2100) Integer completedYear,
            @NotBlank @Size(max = 500) String reason) {}

    public record ComplianceTypeRequest(@NotNull UUID schoolId,
            @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_ ]{1,49}$") String code,
            @NotBlank @Size(max = 140) String name,
            @Size(max = 500) String description, String applicableStaffCategory,
            @Min(1) @Max(240) Integer validityMonths, boolean requiredForActiveService,
            @NotBlank String status, @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record ComplianceCheckRequest(@NotNull UUID schoolId, @NotNull UUID staffProfileId,
            @NotNull UUID complianceTypeId, @Size(max = 160) String safeReference,
            @Size(max = 1000) String restrictedNote,
            @NotBlank @Size(max = 500) String reason) {}

    public record PublicProfileRequest(@NotNull UUID schoolId, @NotNull UUID staffProfileId,
            @Min(1) @Max(1000) int revisionNumber, UUID supersedesRevisionId,
            @NotBlank @Size(max = 120) String displayName,
            @NotBlank @Size(max = 120) String designation,
            @Size(max = 500) String qualificationSummary, @Size(max = 1000) String shortBio,
            String cbseStaffCategory, @Min(0) @Max(500) int displayOrder,
            @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record VersionedReason(@NotNull UUID schoolId, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record EndRequest(@NotNull UUID schoolId, @NotNull LocalDate endDate,
            @Min(0) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

    public record DecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @Min(0) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

    public record ComplianceDecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            LocalDate completedOn, LocalDate validUntil, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}
}
