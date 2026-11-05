package com.educore.api.files;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1")
public class PrivateFileController {

    private final PrivateFileService files;

    public PrivateFileController(PrivateFileService files) {
        this.files = files;
    }

    @PostMapping(value = "/learning/assignments/{assignmentId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CAPABILITY_files.manage') and hasAuthority('CAPABILITY_learning.manage')")
    FileUploadResponse uploadAssignmentResource(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID assignmentId,
            @RequestParam UUID schoolId,
            @RequestParam(required = false) UUID replacesFileId,
            @RequestPart("file") MultipartFile file) {
        return files.uploadAssignmentResource(user, schoolId, assignmentId, replacesFileId, file);
    }

    @PostMapping(value = "/family/children/{learnerId}/assignments/{assignmentId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
    FileUploadResponse uploadFamilySubmissionEvidence(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID assignmentId,
            @RequestParam(required = false) UUID replacesFileId,
            @RequestPart("file") MultipartFile file) {
        return files.uploadFamilySubmissionEvidence(user, learnerId, assignmentId, replacesFileId, file);
    }

    @PostMapping(value = "/student/assignments/{assignmentId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CAPABILITY_portal.student')")
    FileUploadResponse uploadStudentSubmissionEvidence(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID assignmentId,
            @RequestParam(required = false) UUID replacesFileId,
            @RequestPart("file") MultipartFile file) {
        return files.uploadStudentSubmissionEvidence(user, assignmentId, replacesFileId, file);
    }

    @PostMapping("/files/{fileId}/expire")
    FileContextMutationResponse expire(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID fileId,
            @Valid @RequestBody ExpireFileRequest request) {
        return new FileContextMutationResponse(
                files.expire(user, fileId, request.expectedVersion(), request.reason()));
    }

    @GetMapping("/files/{fileId}")
    ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID fileId) {
        PrivateFileDownload file = files.download(user, fileId);
        StreamingResponseBody body = output -> file.writer().writeTo(output);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .contentLength(file.sizeBytes())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
                .body(body);
    }

    public record ExpireFileRequest(
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 240) String reason) {}
}
