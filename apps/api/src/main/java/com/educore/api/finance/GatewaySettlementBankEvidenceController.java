package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Human non-posting bank evidence and independent mistaken-evidence correction only. */
@RestController
@RequestMapping("/api/v1/finance/gateway/settlements")
public class GatewaySettlementBankEvidenceController {
    private final GatewaySettlementBankEvidenceService evidence;
    public GatewaySettlementBankEvidenceController(GatewaySettlementBankEvidenceService evidence){this.evidence=evidence;}
    @ModelAttribute void privateHeaders(HttpServletResponse response){
        response.setHeader("Cache-Control","private, no-store");response.setHeader("Vary","Cookie");response.setHeader("X-Content-Type-Options","nosniff");
    }
    @GetMapping("/requests/{requestId}/bank-evidence")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewaySettlementBankEvidenceModels.Context> context(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID requestId,@RequestParam UUID schoolId){return privateResponse(evidence.context(user,schoolId,requestId));}
    @PostMapping("/requests/{requestId}/bank-evidence")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read') and hasAuthority('CAPABILITY_fees.close.propose')")
    ResponseEntity<GatewaySettlementBankEvidenceModels.Evidence> record(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID requestId,@Valid @RequestBody GatewaySettlementBankEvidenceModels.Command command){return privateResponse(evidence.record(user,requestId,command));}
    @PostMapping("/requests/{requestId}/bank-evidence/{evidenceId}/rejections")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read') and hasAuthority('CAPABILITY_fees.close.approve')")
    ResponseEntity<GatewaySettlementBankEvidenceModels.Evidence> reject(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID requestId,@PathVariable UUID evidenceId,@Valid @RequestBody GatewaySettlementBankEvidenceModels.RejectionCommand command){return privateResponse(evidence.reject(user,requestId,evidenceId,command));}
    private static <T> ResponseEntity<T> privateResponse(T body){return ResponseEntity.ok().header("Cache-Control","private, no-store").header("Vary","Cookie").header("X-Content-Type-Options","nosniff").body(body);}
}
