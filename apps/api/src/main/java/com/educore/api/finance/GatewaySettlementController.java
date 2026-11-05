package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Private staff report access. Report sync never confirms a bank credit or changes money. */
@RestController
@RequestMapping("/api/v1/finance/gateway/settlements")
public class GatewaySettlementController {
    private final GatewaySettlementService settlements;
    public GatewaySettlementController(GatewaySettlementService settlements){this.settlements=settlements;}
    @GetMapping @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewaySettlementModels.Queue> queue(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam UUID schoolId,
            @RequestParam(defaultValue="ALL") @Pattern(regexp="ALL|REQUESTED|SYNCING|RETRYING|COMPLETE|REVIEW_REQUIRED") String status,
            @RequestParam(defaultValue="ALL") @Pattern(regexp="ALL|SUMMARY|EVENTS") String kind,
            @RequestParam(required=false) @Size(max=100) String q,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue="25") @Min(1) @Max(50) int pageSize){return privateResponse(settlements.queue(user,schoolId,status,kind,q,page,pageSize));}
    @PostMapping("/requests") @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read') and hasAuthority('CAPABILITY_fees.close.propose')")
    ResponseEntity<GatewaySettlementModels.Result> submit(@AuthenticationPrincipal AuthenticatedUser user,@Valid @RequestBody GatewaySettlementModels.Submit command){return privateResponse(settlements.submit(user,command));}
    @GetMapping("/requests/{requestId}") @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<GatewaySettlementModels.Report> report(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID requestId,
            @RequestParam UUID schoolId,@RequestParam(required=false) @Size(max=100) String q,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,@RequestParam(defaultValue="25") @Min(1) @Max(50) int pageSize){return privateResponse(settlements.report(user,schoolId,requestId,q,page,pageSize));}
    private static <T> ResponseEntity<T> privateResponse(T body){return ResponseEntity.ok().header("Cache-Control","private, no-store").header("Vary","Cookie").header("X-Content-Type-Options","nosniff").body(body);}
}
