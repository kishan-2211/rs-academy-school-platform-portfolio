package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.educore.api.finance.FamilyGatewayPaymentModels.*;
import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** Boundary/header contracts only; live authentication/CSRF enforcement is an API acceptance gate. */
class FamilyGatewayPaymentControllerTest {
    private final FamilyGatewayPaymentService service = mock(FamilyGatewayPaymentService.class);
    private final FamilyGatewayPaymentController controller = new FamilyGatewayPaymentController(service);
    private final AuthenticatedUser user = mock(AuthenticatedUser.class);
    private final UUID learner = UUID.randomUUID(), attempt = UUID.randomUUID();

    @Test void everyHandlerSharesGuardianCapabilityAndContinuationIsNotAGetSideEffect() throws Exception {
        assertThat(FamilyGatewayPaymentController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasAuthority('CAPABILITY_portal.guardian')");
        var method = FamilyGatewayPaymentController.class.getDeclaredMethod("continueCheckout", AuthenticatedUser.class, UUID.class, UUID.class);
        assertThat(method.getAnnotation(PostMapping.class).value()).containsExactly("/children/{learnerId}/fee-payments/{attemptId}/continue");
        assertThat(method.getAnnotation(GetMapping.class)).isNull();
    }

    @Test void historyPreservesRequestedScopePageAndPrivateNoStoreHeaders() {
        var history = new History(learner, Availability.DISABLED, 2, 20, false, List.of()); when(service.history(user, learner, 2)).thenReturn(history);
        var response = controller.history(user, learner, 2); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(history); privateHeaders(response); verify(service).history(user, learner, 2);
    }

    @Test void initiationDelegatesExactStableTermsAndReturnsCreatedWithoutCallingContinuation() {
        var request = new InitiateRequest(UUID.randomUUID(), List.of(new ChargeSelection(UUID.randomUUID(), BigDecimal.ONE)), "0000000000");
        var payment = payment(); when(service.initiate(user, learner, request)).thenReturn(payment);
        var response = controller.initiate(user, learner, request); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(payment); privateHeaders(response); verify(service).initiate(user, learner, request);
        verify(service, never()).continueCheckout(any(), any(), any());
    }

    @Test void exactAttemptGetCannotSupplyPaidClaimsOrAlternativeLearnerThroughRequestBody() throws Exception {
        var payment = payment(); when(service.payment(user, attempt)).thenReturn(payment);
        var response = controller.payment(user, attempt); assertThat(response.getBody()).isSameAs(payment); privateHeaders(response);
        assertThat(FamilyGatewayPaymentController.class.getDeclaredMethod("payment", AuthenticatedUser.class, UUID.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/fee-payments/{attemptId}");
        verify(service).payment(user, attempt);
    }

    @Test void continuationIsTheOnlyResponseContainingTheAuthenticatedCheckoutUrlAndCannotBeCached() {
        var result = new Continuation(attempt, URI.create("https://payments-test.cashfree.com/links/synthetic_continue"), Instant.parse("2026-10-14T06:30:00Z"));
        when(service.continueCheckout(user, learner, attempt)).thenReturn(result);
        var response = controller.continueCheckout(user, learner, attempt); assertThat(response.getBody()).isSameAs(result); privateHeaders(response);
        verify(service).continueCheckout(user, learner, attempt);
    }

    private Payment payment() {
        var now = Instant.parse("2026-10-14T06:00:00Z"); return new Payment(attempt, learner, UUID.randomUUID(), "Synthetic session", BigDecimal.ONE, "INR",
                Status.PENDING, now, now.plusSeconds(1800), LocalDate.parse("2026-10-14"), true, true, false, List.of(), null);
    }
    private static void privateHeaders(ResponseEntity<?> response) {
        assertThat(response.getHeaders().getCacheControl()).contains("private", "no-store");
        assertThat(response.getHeaders().getVary()).contains("Cookie"); assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
