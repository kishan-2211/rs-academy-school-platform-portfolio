package com.educore.api.reports;

import com.educore.api.identity.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportsController {
    private final ReportsService reports;

    public ReportsController(ReportsService reports) {
        this.reports = reports;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('CAPABILITY_reports.read')")
    ReportsSnapshot overview(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return reports.overview(user, schoolId);
    }
}
