package com.educore.api.payroll;

import com.educore.api.identity.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payroll/accounting/reports")
@PreAuthorize("hasAuthority('CAPABILITY_payroll.read') and hasAuthority('CAPABILITY_accounting.read')")
public class PayrollReportingController {
    private final PayrollReportingService reports;
    public PayrollReportingController(PayrollReportingService reports) { this.reports = reports; }

    @GetMapping("/register")
    PayrollReports.Page<PayrollReports.RegisterRow> register(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID staffProfileId,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return reports.register(user, schoolId, new PayrollReports.Query(from, to, staffProfileId, search, page, size));
    }
    @GetMapping("/reconciliation")
    PayrollReports.Page<PayrollReports.ReconciliationRow> reconciliation(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID staffProfileId,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return reports.reconciliation(user, schoolId, new PayrollReports.Query(from, to, staffProfileId, search, page, size));
    }
    @GetMapping("/advance-reconciliation")
    PayrollReports.AdvancePage<PayrollReports.AdvanceReconciliationRow> advanceReconciliation(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueThrough,
            @RequestParam(required = false) UUID staffProfileId,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return reports.advanceReconciliation(user, schoolId,
                new PayrollReports.AdvanceQuery(dueThrough, staffProfileId, search, page, size));
    }
    @GetMapping("/summary")
    PayrollReports.Page<PayrollReports.SummaryRow> summary(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam String groupBy,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID staffProfileId,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return reports.summary(user, schoolId, new PayrollReports.Query(from, to, staffProfileId, search, page, size),
                PayrollReports.GroupBy.parse(groupBy));
    }
    @GetMapping(value = "/register.csv", produces = "text/csv")
    ResponseEntity<byte[]> registerCsv(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID staffProfileId,
            @RequestParam(defaultValue = "") String search) {
        return download("payroll-register.csv", reports.exportRegister(user, schoolId,
                new PayrollReports.Query(from, to, staffProfileId, search, 0, 100)));
    }
    @GetMapping(value = "/reconciliation.csv", produces = "text/csv")
    ResponseEntity<byte[]> reconciliationCsv(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID staffProfileId,
            @RequestParam(defaultValue = "") String search) {
        return download("payroll-reconciliation.csv", reports.exportReconciliation(user, schoolId,
                new PayrollReports.Query(from, to, staffProfileId, search, 0, 100)));
    }
    @GetMapping(value = "/advance-reconciliation.csv", produces = "text/csv")
    ResponseEntity<byte[]> advanceReconciliationCsv(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueThrough,
            @RequestParam(required = false) UUID staffProfileId,
            @RequestParam(defaultValue = "") String search) {
        return download("payroll-advance-reconciliation.csv",
                reports.exportAdvanceReconciliation(user, schoolId,
                        new PayrollReports.AdvanceQuery(dueThrough, staffProfileId, search, 0, 100)));
    }
    @GetMapping(value = "/summary.csv", produces = "text/csv")
    ResponseEntity<byte[]> summaryCsv(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam String groupBy,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID staffProfileId,
            @RequestParam(defaultValue = "") String search) {
        var grouping = PayrollReports.GroupBy.parse(groupBy);
        return download("payroll-summary-" + grouping.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".csv",
                reports.exportSummary(user, schoolId,
                        new PayrollReports.Query(from, to, staffProfileId, search, 0, 100), grouping));
    }
    private static ResponseEntity<byte[]> download(String filename, String csv) {
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.PRAGMA, "no-cache").header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
