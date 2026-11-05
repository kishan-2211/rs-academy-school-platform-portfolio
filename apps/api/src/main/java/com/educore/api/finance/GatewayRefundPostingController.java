package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/finance/gateway/refunds/{refundId}/posting")
public class GatewayRefundPostingController {
    private final GatewayRefundPostingService postings;
    public GatewayRefundPostingController(GatewayRefundPostingService postings) { this.postings = postings; }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    ResponseEntity<GatewayRefundPostingModels.Context> inspect(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID refundId, @RequestParam UUID schoolId) {
        return privateResponse(postings.inspect(user, schoolId, refundId));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CAPABILITY_fees.refund') and hasAuthority('CAPABILITY_accounting.prepare') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewayRefundPostingModels.Posting> post(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID refundId, @Valid @RequestBody GatewayRefundPostingModels.Command command) {
        return privateResponse(postings.post(user, refundId, command));
    }

    @PostMapping("/journal-repairs")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.refund') and hasAuthority('CAPABILITY_accounting.prepare') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewayRefundPostingModels.JournalRepair> repairJournal(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID refundId, @Valid @RequestBody GatewayRefundPostingModels.JournalRepairCommand command) {
        return privateResponse(postings.repairJournal(user, refundId, command));
    }

    private static <T> ResponseEntity<T> privateResponse(T body) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").header("Vary", "Cookie")
                .header("X-Content-Type-Options", "nosniff").body(body);
    }
}
