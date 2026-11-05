package com.educore.api.students;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/students")
public class StudentRegistryController {

    private final StudentRegistryService students;

    public StudentRegistryController(StudentRegistryService students) {
        this.students = students;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_student.read')")
    StudentPageResponse list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) UUID academicSessionId,
            @RequestParam(required = false) @Size(max = 40) String gradeCode,
            @RequestParam(required = false) @Size(max = 20) String placementStatus,
            @RequestParam(required = false) com.educore.api.admissions.AdmissionType admissionType,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return students.list(user, query, academicSessionId, gradeCode, placementStatus, page, size, admissionType);
    }

    @GetMapping("/{learnerId}")
    @PreAuthorize("hasAuthority('CAPABILITY_student.read') and hasAuthority('CAPABILITY_guardian.read')")
    StudentDetailResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return students.get(user, learnerId);
    }

    @PatchMapping("/{learnerId}")
    @PreAuthorize("hasAuthority('CAPABILITY_student.manage') and hasAuthority('CAPABILITY_guardian.read')")
    StudentDetailResponse updateProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @Valid @RequestBody UpdateStudentProfileRequest request) {
        return students.updateProfile(user, learnerId, request);
    }

    @PatchMapping("/{learnerId}/guardians/{guardianId}")
    @PreAuthorize("hasAuthority('CAPABILITY_student.read') and hasAuthority('CAPABILITY_guardian.manage')")
    StudentDetailResponse updateGuardian(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID guardianId,
            @Valid @RequestBody UpdateGuardianRequest request) {
        return students.updateGuardian(user, learnerId, guardianId, request);
    }

    @GetMapping("/{learnerId}/options")
    @PreAuthorize("hasAuthority('CAPABILITY_student.manage')")
    StudentOptionsResponse options(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return students.options(user, learnerId);
    }

    @PostMapping("/{learnerId}/placements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_student.manage') and hasAuthority('CAPABILITY_guardian.read')")
    StudentDetailResponse place(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @Valid @RequestBody PlaceStudentRequest request) {
        return students.place(user, learnerId, request);
    }

    @PostMapping("/sections")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_student.manage') and hasAuthority('CAPABILITY_academics.configure')")
    SectionOption createSection(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateSectionRequest request) {
        return students.createSection(user, request);
    }
}
