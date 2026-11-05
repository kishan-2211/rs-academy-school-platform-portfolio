package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Private human source review only; no bank-confirmation, provider access or mutation endpoint. */
@RestController
@RequestMapping("/api/v1/finance/gateway/settlements")
public class GatewaySettlementSourceReviewController {
    private final GatewaySettlementSourceReviewService review;
    private final GatewaySettlementRefundCandidateService refunds;
    public GatewaySettlementSourceReviewController(GatewaySettlementSourceReviewService review, GatewaySettlementRefundCandidateService refunds) {
        this.review = review; this.refunds = refunds;
    }

    @ModelAttribute
    void privateHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store"); response.setHeader("Vary", "Cookie");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @GetMapping("/requests/{requestId}/source-review")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewaySettlementSourceReviewModels.Review> review(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID requestId, @RequestParam UUID schoolId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(50) int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").header("Vary", "Cookie")
                .header("X-Content-Type-Options", "nosniff").body(review.review(user, schoolId, requestId, page, pageSize));
    }

    @GetMapping("/requests/{requestId}/refund-candidates")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewaySettlementRefundCandidateModels.Review> refundCandidates(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID requestId, @RequestParam UUID schoolId, @RequestParam UUID sourceReadId,
            @RequestParam @Min(0) @Max(9) int rowIndex,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(50) int pageSize) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").header("Vary", "Cookie")
                .header("X-Content-Type-Options", "nosniff")
                .body(refunds.candidates(user, schoolId, requestId, sourceReadId, rowIndex, page, pageSize));
    }
}
