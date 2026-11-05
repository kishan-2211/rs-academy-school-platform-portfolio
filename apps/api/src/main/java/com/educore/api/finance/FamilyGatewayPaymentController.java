package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/family")
@PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
public class FamilyGatewayPaymentController {
    private final FamilyGatewayPaymentService payments;
    public FamilyGatewayPaymentController(FamilyGatewayPaymentService payments) { this.payments = payments; }

    @GetMapping("/children/{learnerId}/fee-payments")
    ResponseEntity<FamilyGatewayPaymentModels.History> history(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId, @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page) {
        return response(HttpStatus.OK, payments.history(user, learnerId, page));
    }

    @PostMapping("/children/{learnerId}/fee-payments")
    ResponseEntity<FamilyGatewayPaymentModels.Payment> initiate(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId, @Valid @RequestBody FamilyGatewayPaymentModels.InitiateRequest request) {
        return response(HttpStatus.CREATED, payments.initiate(user, learnerId, request));
    }

    @GetMapping("/fee-payments/{attemptId}")
    ResponseEntity<FamilyGatewayPaymentModels.Payment> payment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID attemptId) {
        return response(HttpStatus.OK, payments.payment(user, attemptId));
    }

    @PostMapping("/children/{learnerId}/fee-payments/{attemptId}/continue")
    ResponseEntity<FamilyGatewayPaymentModels.Continuation> continueCheckout(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId, @PathVariable UUID attemptId) {
        return response(HttpStatus.OK, payments.continueCheckout(user, learnerId, attemptId));
    }

    private static <T> ResponseEntity<T> response(HttpStatus status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.VARY, HttpHeaders.COOKIE).header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff").body(body);
    }
}
