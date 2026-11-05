package com.educore.api.studentleave;

import static com.educore.api.studentleave.StudentLeaveModels.*;
import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/student-leave")
@PreAuthorize("hasAuthority('CAPABILITY_student_leave.manage')")
public class StudentLeaveController {
    private final StudentLeaveService leave;
    public StudentLeaveController(StudentLeaveService leave) { this.leave = leave; }

    @GetMapping
    Workspace workspace(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required=false) UUID schoolId, @RequestParam(required=false) String status,
            @RequestParam(defaultValue="0") int page) { return leave.workspace(user, schoolId, status, page); }
    @PutMapping("/policy")
    Mutation policy(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PolicyRequest request) { return leave.savePolicy(user, request); }
    @GetMapping("/{id}")
    Detail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @RequestParam UUID schoolId) { return leave.staffDetail(user, schoolId, id); }
    @PostMapping("/{id}/decision")
    Mutation decide(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @Valid @RequestBody DecisionRequest request) { return leave.decide(user, id, request); }
}
