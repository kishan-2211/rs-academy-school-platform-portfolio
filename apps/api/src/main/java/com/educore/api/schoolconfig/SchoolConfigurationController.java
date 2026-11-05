package com.educore.api.schoolconfig;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
@RequestMapping("/api/v1/school-configuration")
public class SchoolConfigurationController {
    private static final String CONFIGURE =
            "hasAnyAuthority('CAPABILITY_school.configure','CAPABILITY_platform.configure')";

    private final SchoolConfigurationService configuration;

    public SchoolConfigurationController(SchoolConfigurationService configuration) {
        this.configuration = configuration;
    }

    @GetMapping
    @PreAuthorize(CONFIGURE)
    SchoolConfigurationSnapshot snapshot(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return configuration.snapshot(user, schoolId);
    }

    @PutMapping("/profile")
    @PreAuthorize(CONFIGURE)
    SchoolConfigurationMutationResponse updateProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SchoolProfileRequest request) {
        return configuration.updateProfile(user, request);
    }

    @PostMapping("/campuses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(CONFIGURE)
    SchoolConfigurationMutationResponse createCampus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CampusRequest request) {
        return configuration.createCampus(user, request);
    }

    @PutMapping("/campuses/{campusId}")
    @PreAuthorize(CONFIGURE)
    SchoolConfigurationMutationResponse updateCampus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID campusId,
            @Valid @RequestBody CampusRequest request) {
        return configuration.updateCampus(user, campusId, request);
    }

    public record SchoolProfileRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 60) String timeZone,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currencyCode,
            @NotBlank @Size(max = 40) String boardCode,
            @NotBlank @Size(max = 80) String gradeRange,
            @Size(max = 160) String motto,
            @NotEmpty @Size(max = 8) List<@Pattern(regexp = "^[a-z]{2,3}(?:-[A-Z]{2})?$") String> supportedLanguages,
            @Email @Size(max = 254) String publicEmail,
            @Pattern(regexp = "^[+]?[0-9 ()-]{7,30}$") String publicPhone,
            @Size(max = 120) String principalDisplayName,
            @NotBlank @Size(max = 240) String addressLine,
            @Size(max = 120) String locality,
            @NotBlank @Size(max = 120) String city,
            @NotBlank @Size(max = 120) String stateName,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode,
            @Min(0) Integer learnerCountMin,
            @Min(0) Integer learnerCountMax,
            @Min(0) Integer teachingStaffEstimate,
            @Min(0) Integer operationsStaffEstimate,
            @NotNull @Min(0) Long expectedVersion,
            @AssertTrue boolean accuracyConfirmed,
            @NotBlank @Size(max = 500) String reason) {}

    public record CampusRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 30) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,29}$") String code,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 60) String timeZone,
            @NotBlank @Pattern(regexp = "^(?i:ACTIVE|INACTIVE)$") String status,
            @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}
}
