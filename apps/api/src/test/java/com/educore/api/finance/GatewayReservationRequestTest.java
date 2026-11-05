package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayReservationRequestTest {
    @Test void omittedAccountRetainsLegacyModeWhileExplicitSelectionIsFrozenAndPrivate() {
        UUID account = UUID.randomUUID(), requestId = UUID.randomUUID();
        var charges = List.of(selection(UUID.randomUUID(), "10.00"));
        var legacy = new GatewayReservationService.PrepareRequest(requestId, charges, "0000000000");
        var explicit = new GatewayReservationService.PrepareRequest(requestId, charges, "0000000000", account);
        assertThat(legacy.accountId()).isNull();
        assertThat(legacy.accountSelectionMode()).isEqualTo("CURRENT_SESSION");
        assertThat(explicit.accountId()).isEqualTo(account);
        assertThat(explicit.accountSelectionMode()).isEqualTo("EXPLICIT_ACCOUNT");
        assertThat(explicit.toString()).doesNotContain(account.toString(), requestId.toString(), "0000000000");
    }
    @Test void snapshotsAndCanonicalizesSelectedChargesWithoutRetainingMutableInput() {
        UUID low = new UUID(0, 1), high = new UUID(0, 2);
        var input = new ArrayList<>(List.of(selection(high, "100.00"), selection(low, "10.50")));
        var request = request(input);
        input.clear();
        assertThat(request.allocations()).extracting(GatewayReservationService.ChargeSelection::chargeId).containsExactly(low, high);
        assertThatThrownBy(() -> request.allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsDuplicateChargesAndUnboundedSelections() {
        var one = selection(UUID.randomUUID(), "1.00");
        assertThatThrownBy(() -> request(List.of(one, one))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(java.util.stream.IntStream.range(0,101).mapToObj(i -> selection(UUID.randomUUID(), "1.00")).toList()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsInvalidAmountsAndAggregateOverflowRatherThanRounding() {
        for (String value : List.of("0", "-1", "1.001", "100000000.01")) {
            assertThatThrownBy(() -> selection(UUID.randomUUID(), value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> request(List.of(selection(UUID.randomUUID(), "99999999.99"), selection(UUID.randomUUID(), "0.02"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void requiresStableRequestAndChargeIdentitiesAndPhoneWithoutLoggingThem() {
        assertThatThrownBy(() -> new GatewayReservationService.PrepareRequest(null, List.of(selection(UUID.randomUUID(), "10")), "0000000000"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> selection(null, "10")).isInstanceOf(NullPointerException.class);
        for (String phone : List.of("+910000000000", "000 0000000", "000000000", "not-a-number")) {
            assertThatThrownBy(() -> new GatewayReservationService.PrepareRequest(UUID.randomUUID(), List.of(selection(UUID.randomUUID(), "10")), phone))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(request(List.of(selection(UUID.randomUUID(), "10"))).toString()).isEqualTo("PrepareRequest[sensitive payment intent]");
    }
    @Test void newCheckoutExpiryChoosesWholeSecondsBeforePersistenceWithoutRewritingItsCreationTime() {
        var created=java.time.Instant.parse("2026-10-13T18:29:59.123456789Z");
        assertThat(GatewayReservationService.newCheckoutExpiresAt(created)).isEqualTo(java.time.Instant.parse("2026-10-13T18:59:59Z"));
        assertThat(created).isEqualTo(java.time.Instant.parse("2026-10-13T18:29:59.123456789Z"));
    }
    @Test void newCheckoutExpiryNeverExtendsTtlOrRoundsOverASubsecondBoundary() {
        for (String value : List.of("2026-10-13T23:59:59.999999999Z","2026-12-31T23:45:00.000000001Z",
                "2026-10-13T12:00:00Z","2026-10-13T12:00:00.500Z")) {
            var created=java.time.Instant.parse(value);
            var expires=GatewayReservationService.newCheckoutExpiresAt(created);
            assertThat(expires.getNano()).isZero();
            assertThat(expires).isAfter(created).isBeforeOrEqualTo(created.plusSeconds(1800));
            assertThat(java.time.Duration.between(created,expires)).isGreaterThan(java.time.Duration.ofSeconds(1799));
        }
    }
    @Test void absentOrUnrepresentableCreationTimeCannotInventAnExpiry() {
        assertThatThrownBy(()->GatewayReservationService.newCheckoutExpiresAt(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(()->GatewayReservationService.newCheckoutExpiresAt(java.time.Instant.MAX)).isInstanceOf(java.time.DateTimeException.class);
    }
    private static GatewayReservationService.ChargeSelection selection(UUID id, String amount) {
        return new GatewayReservationService.ChargeSelection(id, new BigDecimal(amount));
    }
    private static GatewayReservationService.PrepareRequest request(List<GatewayReservationService.ChargeSelection> input) {
        return new GatewayReservationService.PrepareRequest(UUID.randomUUID(), input, "0000000000");
    }
}
