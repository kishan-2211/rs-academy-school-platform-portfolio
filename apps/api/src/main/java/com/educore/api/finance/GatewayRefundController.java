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
public class GatewayRefundController {
    private final GatewayRefundService refunds;
    public GatewayRefundController(GatewayRefundService refunds){this.refunds=refunds;}
    @GetMapping("/refunds") @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    ResponseEntity<GatewayRefundModels.Queue> queue(@AuthenticationPrincipal AuthenticatedUser user,@RequestParam UUID schoolId,
            @RequestParam(defaultValue="SUBMITTED") @Pattern(regexp="ALL|SUBMITTED|APPROVED|REJECTED|CANCELLED") String status,
            @RequestParam(required=false) @Size(max=100) String q,@RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue="25") @Min(1) @Max(50) int pageSize){return privateResponse(refunds.queue(user,schoolId,status,q,page,pageSize));}
    @GetMapping("/payments/{paymentId}/refunds") @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    ResponseEntity<GatewayRefundModels.Payment> payment(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID paymentId,@RequestParam UUID schoolId){return privateResponse(refunds.payment(user,schoolId,paymentId));}
    @PostMapping("/payments/{paymentId}/refunds") @PreAuthorize("hasAuthority('CAPABILITY_fees.refund')")
    ResponseEntity<GatewayRefundModels.Result> submit(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID paymentId,@Valid @RequestBody GatewayRefundModels.Submit command){return privateResponse(refunds.submit(user,paymentId,command));}
    @PostMapping("/refunds/{refundId}/decision") @PreAuthorize("hasAuthority('CAPABILITY_fees.refund')")
    ResponseEntity<GatewayRefundModels.Result> decide(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable UUID refundId,@Valid @RequestBody GatewayRefundModels.DecisionCommand command){return privateResponse(refunds.decide(user,refundId,command));}
    private static <T> ResponseEntity<T> privateResponse(T body){return ResponseEntity.ok().header("Cache-Control","private, no-store").header("Vary","Cookie").header("X-Content-Type-Options","nosniff").body(body);}
}
