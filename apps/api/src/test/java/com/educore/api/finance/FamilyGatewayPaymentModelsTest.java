package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.educore.api.finance.FamilyGatewayPaymentModels.*;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FamilyGatewayPaymentModelsTest {
    @Test void oldJsonWithoutAccountKeepsLegacySelectionAndExplicitAccountRemainsOptionalValidatedTerms() {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        UUID requestId = UUID.randomUUID(), chargeId = UUID.randomUUID(), accountId = UUID.randomUUID();
        String legacyJson = "{\"clientRequestId\":\"" + requestId + "\",\"allocations\":[{\"chargeId\":\"" + chargeId
                + "\",\"amount\":12.34}],\"payerPhone\":\"0000000000\"}";
        var legacy = mapper.readValue(legacyJson, InitiateRequest.class);
        assertThat(legacy.accountId()).isNull();
        assertThat(legacy.clientRequestId()).isEqualTo(requestId);
        var explicit = mapper.readValue(legacyJson.substring(0, legacyJson.length() - 1)
                + ",\"accountId\":\"" + accountId + "\"}", InitiateRequest.class);
        assertThat(explicit.accountId()).isEqualTo(accountId);
        assertThat(explicit.allocations()).isEqualTo(legacy.allocations());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(legacy)).isEmpty();
            assertThat(factory.getValidator().validate(explicit)).isEmpty();
        }
        assertThat(explicit.toString()).doesNotContain(accountId.toString(), "0000000000");
    }
    @Test void validSyntheticTermsPassBeanValidationWithoutRelaxingAmountPrecision() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new InitiateRequest(UUID.randomUUID(), List.of(new ChargeSelection(UUID.randomUUID(), new BigDecimal("750.00"))), "0000000000");
            assertThat(factory.getValidator().validate(request)).isEmpty();
            for (String amount : List.of("0", "-1", "1.001", "100000000.01")) {
                assertThat(factory.getValidator().validate(new InitiateRequest(UUID.randomUUID(),
                        List.of(new ChargeSelection(UUID.randomUUID(), new BigDecimal(amount))), "0000000000"))).isNotEmpty();
            }
        }
    }

    @Test void missingIdentitySelectionAmountAndInvalidPayerPhoneAreInvalid() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator(); var valid = new ChargeSelection(UUID.randomUUID(), BigDecimal.ONE);
            for (String phone : List.of("", "+910000000000", "000 0000000", "000000000", "not-a-phone")) {
                assertThat(validator.validate(new InitiateRequest(UUID.randomUUID(), List.of(valid), phone))).isNotEmpty();
            }
            assertThat(validator.validate(new InitiateRequest(null, List.of(valid), "0000000000"))).isNotEmpty();
            assertThat(validator.validate(new InitiateRequest(UUID.randomUUID(), null, "0000000000"))).isNotEmpty();
            assertThat(validator.validate(new InitiateRequest(UUID.randomUUID(), List.of(), "0000000000"))).isNotEmpty();
            assertThat(validator.validate(new InitiateRequest(UUID.randomUUID(), List.of(new ChargeSelection(null, null)), "0000000000"))).isNotEmpty();
        }
    }

    @Test void requestAndResponseListsSnapshotMutableInputs() {
        var allocations = new ArrayList<>(List.of(new ChargeSelection(UUID.randomUUID(), BigDecimal.ONE)));
        var request = new InitiateRequest(UUID.randomUUID(), allocations, "0000000000"); allocations.clear();
        assertThat(request.allocations()).hasSize(1); assertThatThrownBy(() -> request.allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
        var selected = new ArrayList<>(List.of(new SelectedCharge(UUID.randomUUID(), "Synthetic charge", LocalDate.parse("2026-10-01"), BigDecimal.ONE)));
        var payment = payment(selected); selected.clear(); assertThat(payment.selectedCharges()).hasSize(1);
        var entries = new ArrayList<>(List.of(payment)); var history = new History(payment.learnerId(), Availability.DISABLED, 0, 20, false, entries);
        entries.clear(); assertThat(history.payments()).hasSize(1); assertThatThrownBy(() -> history.payments().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void duplicateChargeIdentitiesFailHttpBeanValidationBeforeAnyServiceCall() {
        var selection = new ChargeSelection(UUID.randomUUID(), BigDecimal.ONE);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(new InitiateRequest(UUID.randomUUID(), List.of(selection, selection), "0000000000"))).isNotEmpty();
        }
    }

    @Test void historyAndPaymentHaveNoProviderCredentialsContactRawErrorsOrCheckoutUrlFields() {
        for (Class<?> model : List.of(Payment.class, History.class, Receipt.class, SelectedCharge.class)) {
            assertThat(java.util.Arrays.stream(model.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName))
                    .doesNotContain("checkoutUrl", "routeKey", "credentialsSecretReference", "payerPhone", "ciphertext", "lastErrorCode", "paymentReference", "orderReference");
        }
    }

    @Test void sensitiveRequestAndContinuationToStringNeverDiscloseContactOrBearerUrl() {
        var request = new InitiateRequest(UUID.randomUUID(), List.of(new ChargeSelection(UUID.randomUUID(), BigDecimal.ONE)), "0000000000");
        assertThat(request.toString()).isEqualTo("InitiateRequest[private family fee payment terms]");
        var continuation = new Continuation(UUID.randomUUID(), URI.create("https://payments-test.cashfree.com/links/synthetic_private"), Instant.parse("2026-10-14T06:30:00Z"));
        assertThat(continuation.toString()).isEqualTo("Continuation[private authenticated checkout redirect]");
    }

    @Test void availabilityIsExplicitAndStatusCannotAcceptABrowserSuccessLabel() {
        assertThat(Availability.values()).containsExactly(Availability.AVAILABLE, Availability.DISABLED, Availability.UNAVAILABLE);
        assertThat(Status.values()).containsExactly(Status.PAID, Status.REVIEW_REQUIRED, Status.READY, Status.PENDING, Status.EXPIRED, Status.RELEASED);
        assertThatThrownBy(() -> Status.valueOf("browser_success")).isInstanceOf(IllegalArgumentException.class);
    }

    private static Payment payment(List<SelectedCharge> selected) {
        var now = Instant.parse("2026-10-14T06:00:00Z");
        return new Payment(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Synthetic session", BigDecimal.ONE, "INR", Status.PENDING,
                now, now.plusSeconds(1800), LocalDate.parse("2026-10-14"), true, true, false, selected, null);
    }
}
