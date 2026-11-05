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
@RequestMapping("/api/v1/family/children/{learnerId}/leave")
@PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
public class FamilyStudentLeaveController {
    private final StudentLeaveService leave;
    public FamilyStudentLeaveController(StudentLeaveService leave) { this.leave = leave; }
    @GetMapping
    FamilyWorkspace workspace(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @RequestParam(defaultValue="0") int page) { return leave.family(user, learnerId, page); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    Mutation submit(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @Valid @RequestBody SubmitRequest request) { return leave.submit(user, learnerId, request); }
    @GetMapping("/{id}")
    Detail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @PathVariable UUID id) { return leave.familyDetail(user, learnerId, id); }
    @PostMapping("/{id}/withdrawal")
    Mutation withdraw(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID learnerId,
            @PathVariable UUID id, @Valid @RequestBody WithdrawalRequest request) {
        return leave.withdraw(user, learnerId, id, request);
    }
}
