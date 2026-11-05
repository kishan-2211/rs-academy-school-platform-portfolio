package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance/gateway")
public class RefundAccountReopenController {
    private final RefundAccountReopenService reviews;
    public RefundAccountReopenController(RefundAccountReopenService reviews) { this.reviews = reviews; }

    @GetMapping("/refunds/{refundId}/account-reopening")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    ResponseEntity<RefundAccountReopenModels.Context> context(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID refundId, @RequestParam UUID schoolId) {
        return privateResponse(reviews.context(user, schoolId, refundId));
    }

    @PostMapping("/refunds/{refundId}/account-reopening")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.refund')")
    ResponseEntity<RefundAccountReopenModels.Result> submit(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID refundId, @Valid @RequestBody RefundAccountReopenModels.Submit command) {
        return privateResponse(reviews.submit(user, refundId, command));
    }

    @GetMapping("/refund-account-reopenings")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    ResponseEntity<RefundAccountReopenModels.Queue> queue(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam UUID schoolId,
            @RequestParam(defaultValue = "SUBMITTED") @Pattern(regexp = "ALL|SUBMITTED|APPROVED|REJECTED|CANCELLED") String status,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(50) int pageSize) {
        return privateResponse(reviews.queue(user, schoolId, status, q, page, pageSize));
    }

    @PostMapping("/refund-account-reopenings/{reviewId}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.refund')")
    ResponseEntity<RefundAccountReopenModels.Result> decide(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID reviewId, @Valid @RequestBody RefundAccountReopenModels.DecisionCommand command) {
        return privateResponse(reviews.decide(user, reviewId, command));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").header("Vary", "Cookie")
                .header("X-Content-Type-Options", "nosniff").body(body);
    }
}
