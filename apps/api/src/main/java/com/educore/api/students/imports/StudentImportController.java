package com.educore.api.students.imports;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
@RequestMapping("/api/v1/student-imports")
public class StudentImportController {

    private static final String IMPORT_AUTHORITY = "hasAuthority('CAPABILITY_student.import')";
    private static final String COMMIT_AUTHORITIES = IMPORT_AUTHORITY
            + " and hasAuthority('CAPABILITY_student.manage')"
            + " and hasAuthority('CAPABILITY_guardian.manage')";

    private final StudentImportService imports;

    public StudentImportController(StudentImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/options")
    @PreAuthorize(IMPORT_AUTHORITY)
    StudentImportOptionsResponse options(@AuthenticationPrincipal AuthenticatedUser user) {
        return imports.options(user);
    }

    @GetMapping
    @PreAuthorize(IMPORT_AUTHORITY)
    StudentImportPageResponse list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return imports.list(user, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(COMMIT_AUTHORITIES)
    StudentImportBatchDetail preview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PreviewStudentImportRequest request) {
        return imports.preview(user, request);
    }

    @GetMapping("/{batchId}")
    @PreAuthorize(IMPORT_AUTHORITY)
    StudentImportBatchDetail get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID batchId) {
        return imports.get(user, batchId);
    }

    @PatchMapping("/{batchId}/rows/{rowId}")
    @PreAuthorize(COMMIT_AUTHORITIES)
    StudentImportBatchDetail resolve(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID batchId,
            @PathVariable UUID rowId,
            @Valid @RequestBody ResolveStudentImportRowRequest request) {
        return imports.resolve(user, batchId, rowId, request);
    }

    @PostMapping("/{batchId}/commits")
    @PreAuthorize(COMMIT_AUTHORITIES)
    StudentImportBatchDetail commit(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID batchId,
            @Valid @RequestBody CommitStudentImportRequest request) {
        return imports.commit(user, batchId, request);
    }
}
