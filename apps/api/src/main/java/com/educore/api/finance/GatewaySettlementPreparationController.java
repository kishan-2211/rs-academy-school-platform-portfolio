package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Human-only preparation. Common accounting remains the independent journal decision surface. */
@RestController
@RequestMapping("/api/v1/finance/gateway/settlements")
public class GatewaySettlementPreparationController {
    private final GatewaySettlementPreparationService service;
    public GatewaySettlementPreparationController(GatewaySettlementPreparationService service){this.service=service;}
    @ModelAttribute void privateHeaders(HttpServletResponse response){response.setHeader("Cache-Control","private, no-store");response.setHeader("Vary","Cookie");response.setHeader("X-Content-Type-Options","nosniff");}

    @GetMapping("/requests/{requestId}/preparation-context")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewaySettlementPreparationModels.Context> context(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID requestId,
            @RequestParam UUID schoolId,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue="25") @Min(1) @Max(50) int pageSize){return ResponseEntity.ok(service.context(user,schoolId,requestId,page,pageSize));}

    @GetMapping("/preparations/{preparationId}")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewaySettlementPreparationModels.PreparationDetail> detail(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID preparationId,@RequestParam UUID schoolId){return ResponseEntity.ok(service.detail(user,schoolId,preparationId));}

    @PostMapping("/requests/{requestId}/preparations")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read') and hasAuthority('CAPABILITY_fees.close.propose') and hasAuthority('CAPABILITY_accounting.prepare')")
    ResponseEntity<GatewaySettlementPreparationModels.Preparation> prepare(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID requestId,
            @Valid @RequestBody GatewaySettlementPreparationModels.Command command){return ResponseEntity.ok(service.prepare(user,requestId,command));}

    @PostMapping("/preparations/{preparationId}/abandonment")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read') and hasAuthority('CAPABILITY_fees.close.propose') and hasAuthority('CAPABILITY_accounting.prepare')")
    ResponseEntity<GatewaySettlementPreparationModels.AbandonmentResult> abandon(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID preparationId,
            @Valid @RequestBody GatewaySettlementPreparationModels.AbandonmentCommand command){return ResponseEntity.ok(service.abandon(user,preparationId,command));}
}
