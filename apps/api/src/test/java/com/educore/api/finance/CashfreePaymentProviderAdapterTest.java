package com.educore.api.finance;

import static com.educore.api.finance.CashfreePaymentProviderAdapter.*;
import static com.educore.api.finance.PaymentProviderAdapter.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic provider fixtures only: no gateway connection or merchant acceptance claim. */
class CashfreePaymentProviderAdapterTest {
    private static final Instant NOW = Instant.parse("2030-04-10T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final URI ORIGIN = URI.create("https://school.example.invalid");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final UUID REFUND = UUID.fromString("00000000-0000-4000-8000-000000000103");
    private static final UUID CANCELLATION = UUID.fromString("00000000-0000-4000-8000-000000000104");
    private static final String ORDER = "synthetic_order_001";
    private static final String PAYMENT = "1234567890123456789";
    private static final String SECRET = "synthetic-secret-not-live";
    private static final String CLIENT_ID = "synthetic-client-not-live";
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final String TIME = NOW.minusSeconds(60).toString();
    private static final String EXPIRY = NOW.plusSeconds(3600).toString();
    private static final String TIMESTAMP = Long.toString(NOW.toEpochMilli());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void createsOnlyAnOpaquePersistedLinkWithNativeRetryKeyAndNoProviderMessages() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        respond(client, sent, link("ACTIVE", "0.00"), link("ACTIVE", "0.00"));
        var adapter = adapter(client);
        assertThat(adapter.createHostedCheckout(request())).isEqualTo(adapter.createHostedCheckout(request()));
        assertThat(sent).hasSize(2);
        for (HttpRequest wire : sent) {
            assertThat(wire.uri().toString()).isEqualTo("https://sandbox.cashfree.com/pg/links");
            assertThat(wire.method()).isEqualTo("POST");
            assertThat(wire.timeout()).contains(Duration.ofSeconds(20));
            assertThat(wire.headers().firstValue("x-api-version")).contains("2026-01-01");
            assertThat(wire.headers().firstValue("x-idempotency-key")).contains(ATTEMPT.toString());
            assertThat(wire.headers().firstValue("x-client-id")).contains(CLIENT_ID);
            assertThat(wire.headers().firstValue("x-client-secret")).contains(SECRET);
            JsonNode body = JSON.readTree(publishedBytes(wire));
            assertThat(body.get("link_id").asString()).isEqualTo(ATTEMPT.toString());
            assertThat(body.get("link_amount").asDecimal()).isEqualByComparingTo(AMOUNT);
            assertThat(body.get("customer_details").size()).isEqualTo(1);
            assertThat(body.get("customer_details").get("customer_phone").asString()).isEqualTo("0000000000");
            assertThat(body.get("link_partial_payments").booleanValue()).isFalse();
            assertThat(body.get("link_auto_reminders").booleanValue()).isFalse();
            for (String flag : List.of("send_sms", "send_email", "send_whatsapp")) {
                assertThat(body.get("link_notify").get(flag).booleanValue()).isFalse();
            }
            assertThat(body.get("link_meta").get("return_url").asString()).isEqualTo(returnUri().toString());
            assertThat(body.toString()).doesNotContain(ACCOUNT.toString(), "customer_name", "customer_email", "bank", "card");
        }
        assertThat(publishedBytes(sent.get(0))).isEqualTo(publishedBytes(sent.get(1)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void rejectsLegacyCreationBeforeAnyHttpExchange() {
        HttpClient client = client();
        var adapter = adapter(client);
        assertCode(() -> adapter.createHostedCheckout(ACCOUNT, AMOUNT, "INR", returnUri()), FailureCode.INVALID_REQUEST);
        verifyNoExchange(client);
    }

    @Test
    void requiresExactReturnOriginFutureBoundedExpiryAndExplicitSyntheticPayerPhone() {
        HttpClient client = client();
        var adapter = adapter(client);
        for (String phone : new String[] {null, "", "000000000", "00000000000", "+910000000000", "000000000a"}) {
            assertCode(() -> adapter.createHostedCheckout(command(NOW.plusSeconds(1), returnUri(), phone)), FailureCode.INVALID_REQUEST);
        }
        for (Instant expiry : List.of(NOW, NOW.minusSeconds(1), NOW.plusSeconds(86401))) {
            assertCode(() -> adapter.createHostedCheckout(command(expiry, returnUri(), "0000000000")), FailureCode.INVALID_REQUEST);
        }
        for (String address : List.of("https://other.example.invalid/family/payments/" + ATTEMPT,
                returnUri() + "?success=true", returnUri() + "#paid", ORIGIN + "/family/payments/other")) {
            assertCode(() -> adapter.createHostedCheckout(command(NOW.plusSeconds(1), URI.create(address), "0000000000")), FailureCode.INVALID_REQUEST);
        }
        verifyNoExchange(client);
    }

    @Test
    void permitsExplicitTestLoopbackButRejectsUnsafeOriginsAndRedirectingClients() {
        HttpClient client = client();
        for (String address : List.of("http://localhost:3000", "http://127.0.0.1:3000", "http://[::1]:3000")) {
            assertThat(new CashfreePaymentProviderAdapter(Environment.TEST, CLIENT_ID, SECRET, URI.create(address), CLOCK, client)
                    .providerCode()).isEqualTo("CASHFREE");
            assertCode(() -> new CashfreePaymentProviderAdapter(Environment.LIVE, CLIENT_ID, SECRET, URI.create(address), CLOCK, client), FailureCode.INVALID_REQUEST);
        }
        for (String address : List.of("http://school.example.invalid", "https://user@school.example.invalid",
                ORIGIN + "/path", ORIGIN + "?next=/", ORIGIN + "#path", "https://school.example.invalid:0")) {
            assertCode(() -> new CashfreePaymentProviderAdapter(Environment.TEST, CLIENT_ID, SECRET, URI.create(address), CLOCK, client), FailureCode.INVALID_REQUEST);
        }
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertCode(() -> adapter(client), FailureCode.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://payments.cashfree.com/links/synthetic", "http://payments-test.cashfree.com/links/synthetic",
            "https://payments-test.cashfree.com.evil.invalid/links/synthetic", "https://user@payments-test.cashfree.com/links/synthetic",
            "https://payments-test.cashfree.com:443/links/synthetic", "https://payments-test.cashfree.com/links/synthetic?next=x",
            "https://payments-test.cashfree.com/links/synthetic#paid", "https://payments-test.cashfree.com/links/%2fother",
            "https://payments-test.cashfree.com/links/../synthetic", "https://payments-test.cashfree.com/links//synthetic"})
    void rejectsEveryUnsafeHostedRedirect(String redirect) throws Exception {
        HttpClient client = client();
        respond(client, new ArrayList<>(), link("ACTIVE", "0.00").replace("https://payments-test.cashfree.com/links/synthetic", redirect));
        assertCode(() -> adapter(client).createHostedCheckout(request()), FailureCode.UNKNOWN);
        verify(client, times(1)).send(any(HttpRequest.class), anyHandler());
    }

    @Test
    void liveUsesOnlyTheFixedLiveApiAndHostedLinkHost() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        respond(client, sent, link("ACTIVE", "0.00").replace("payments-test.cashfree.com", "payments.cashfree.com"));
        var adapter = new CashfreePaymentProviderAdapter(Environment.LIVE, CLIENT_ID, SECRET, ORIGIN, CLOCK, client);
        assertThat(adapter.createHostedCheckout(request()).redirectUri().getHost()).isEqualTo("payments.cashfree.com");
        assertThat(sent.getFirst().uri().toString()).isEqualTo("https://api.cashfree.com/pg/links");
    }

    @Test
    void rejectsCreationResponseMismatchWithoutRetryingTheMutation() throws Exception {
        for (String body : List.of(link("ACTIVE", "0.00").replace(ATTEMPT.toString(), ACCOUNT.toString()),
                link("ACTIVE", "0.00").replace("\"INR\"", "\"USD\""),
                link("ACTIVE", "0.00").replace("100.00", "100.01"),
                link("ACTIVE", "0.00").replace(EXPIRY, NOW.plusSeconds(7200).toString()),
                link("PAID", "100.00"), link("ACTIVE", "0.00").replace("false", "true"))) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), body);
            assertCode(() -> adapter(client).createHostedCheckout(request()), FailureCode.UNKNOWN);
            verify(client, times(1)).send(any(HttpRequest.class), anyHandler());
        }
    }

    @Test
    void fetchesAllScopedUniqueOrdersAndPreservesPaidAsAnObservation() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        respond(client, sent, link("PAID", "100.00"), "[" + order(ORDER) + "]");
        var observed = adapter(client).fetchCheckout(ATTEMPT.toString());
        assertThat(observed.checkoutReference()).isEqualTo(ATTEMPT.toString());
        assertThat(observed.attemptReference()).isEqualTo(ATTEMPT.toString());
        assertThat(observed.status()).isEqualTo(CheckoutStatus.PAID);
        assertThat(observed.orderReferences()).containsExactly(ORDER);
        assertThat(observed.amountPaid()).isEqualByComparingTo("100.00");
        assertThat(sent.get(1).uri().toString()).endsWith("/links/" + ATTEMPT + "/orders?status=ALL");
        assertThat(sent).allMatch(wire -> wire.method().equals("GET"));
    }

    @Test
    void recoversExistingHostedRedirectAndExactObservationUsingOnlyOneLinkGetAndOrderDiscovery() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        respond(client, sent, link("ACTIVE", "0.00"), "[" + order(ORDER) + "]");
        var lookup = adapter(client).lookupHostedCheckout(ATTEMPT.toString());
        assertThat(lookup.observation()).isEqualTo(new CheckoutObservation(ATTEMPT.toString(), ATTEMPT.toString(),
                AMOUNT, "INR", new BigDecimal("0.00"), CheckoutStatus.CREATED, Instant.parse(EXPIRY), List.of(ORDER)));
        assertThat(lookup.hostedCheckout()).contains(new HostedCheckout(ATTEMPT.toString(),
                URI.create("https://payments-test.cashfree.com/links/synthetic")));
        assertThat(sent).extracting(wire -> wire.uri().toString()).containsExactly(
                "https://sandbox.cashfree.com/pg/links/" + ATTEMPT,
                "https://sandbox.cashfree.com/pg/links/" + ATTEMPT + "/orders?status=ALL");
        for (HttpRequest wire : sent) {
            assertThat(wire.method()).isEqualTo("GET");
            assertThat(wire.bodyPublisher()).isEmpty();
            assertThat(wire.headers().firstValue("x-idempotency-key")).isEmpty();
            assertThat(wire.timeout()).contains(Duration.ofSeconds(20));
        }
        assertThat(lookup.toString()).doesNotContain("https://", "/links/synthetic", SECRET, CLIENT_ID);
        assertThat(lookup.hostedCheckout().orElseThrow().toString()).doesNotContain("https://", "/links/synthetic", SECRET, CLIENT_ID);
    }

    @Test
    void liveLookupRecoversOnlyTheExistingLiveHostedLinkWithoutCreatingAnotherCheckout() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        respond(client, sent, link("ACTIVE", "0.00").replace("payments-test.cashfree.com", "payments.cashfree.com"), "[]");
        var adapter = new CashfreePaymentProviderAdapter(Environment.LIVE, CLIENT_ID, SECRET, ORIGIN, CLOCK, client);
        var lookup = adapter.lookupHostedCheckout(ATTEMPT.toString());
        assertThat(lookup.hostedCheckout().orElseThrow().providerOrderReference()).isEqualTo(ATTEMPT.toString());
        assertThat(lookup.hostedCheckout().orElseThrow().redirectUri().getHost()).isEqualTo("payments.cashfree.com");
        assertThat(sent).hasSize(2).allMatch(wire -> wire.method().equals("GET") && wire.uri().getHost().equals("api.cashfree.com"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "CANCELLED", "EXPIRED"})
    void terminalLookupPreservesObservationWhenTheProviderOmitsOrNullsItsHostedUrl(String status) throws Exception {
        String redirectField = ",\"link_url\":\"https://payments-test.cashfree.com/links/synthetic\"";
        String body = link(status, status.equals("PAID") ? "100.00" : "0.00");
        for (String wire : List.of(body.replace(redirectField, ""), body.replace(redirectField, ",\"link_url\":null"))) {
            HttpClient client = client();
            List<HttpRequest> sent = new ArrayList<>();
            respond(client, sent, wire, "[" + order(ORDER) + "]");
            var lookup = adapter(client).lookupHostedCheckout(ATTEMPT.toString());
            assertThat(lookup.hostedCheckout()).isEmpty();
            assertThat(lookup.observation().status()).isEqualTo(CheckoutStatus.valueOf(status));
            assertThat(lookup.observation().checkoutReference()).isEqualTo(ATTEMPT.toString());
            assertThat(lookup.observation().amount()).isEqualByComparingTo(AMOUNT);
            assertThat(lookup.observation().orderReferences()).containsExactly(ORDER);
            assertThat(sent).hasSize(2).allMatch(request -> request.method().equals("GET"));
        }
    }

    @Test
    void lookupRejectsWrongIdentityCurrencyAmountsAndUnsafeOrMissingActiveRedirectWithoutAnyPost() throws Exception {
        String active = link("ACTIVE", "0.00");
        for (String wire : List.of(active.replace(ATTEMPT.toString(), ACCOUNT.toString()),
                active.replace("\"INR\"", "\"USD\""), active.replace("100.00", "100.001"),
                link("ACTIVE", "101.00"), link("UNRECOGNIZED", "0.00"),
                active.replace("https://payments-test.cashfree.com/links/synthetic", "https://evil.invalid/links/synthetic"),
                active.replace("https://payments-test.cashfree.com/links/synthetic", "https://payments.cashfree.com/links/synthetic"),
                active.replace("https://payments-test.cashfree.com/links/synthetic", "http://payments-test.cashfree.com/links/synthetic"),
                active.replace("\"link_url\":\"https://payments-test.cashfree.com/links/synthetic\"", "\"link_url\":null"),
                active.replace(",\"link_url\":\"https://payments-test.cashfree.com/links/synthetic\"", ""))) {
            HttpClient client = client();
            List<HttpRequest> sent = new ArrayList<>();
            respond(client, sent, wire);
            assertCode(() -> adapter(client).lookupHostedCheckout(ATTEMPT.toString()), FailureCode.UNKNOWN);
            assertThat(sent).hasSize(1).allMatch(request -> request.method().equals("GET"));
        }
    }

    @Test
    void lookupRejectsForeignOrderDiscoveryAndNeverTreatsAnUnvalidatedLinkAsRecovered() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        respond(client, sent, link("ACTIVE", "0.00"), "[" + order(ORDER).replace(ATTEMPT.toString(), ACCOUNT.toString()) + "]");
        assertCode(() -> adapter(client).lookupHostedCheckout(ATTEMPT.toString()), FailureCode.UNKNOWN);
        assertThat(sent).hasSize(2).allMatch(request -> request.method().equals("GET"));
    }

    @Test
    void lookupTimeoutAndNotFoundAtEitherReadStageRemainUnknownWithoutCreatingOrRetrying() throws Exception {
        for (boolean duringOrders : new boolean[] {false, true}) {
            for (boolean timeout : new boolean[] {false, true}) {
                HttpClient client = client();
                List<HttpRequest> sent = new ArrayList<>();
                var rejectedBody = new ClosingStream("synthetic-sensitive-body".getBytes(StandardCharsets.UTF_8));
                when(client.send(any(HttpRequest.class), anyHandler())).thenAnswer(call -> {
                    sent.add(call.getArgument(0));
                    if (duringOrders && sent.size() == 1) {
                        return response(200, new ClosingStream(link("ACTIVE", "0.00").getBytes(StandardCharsets.UTF_8)));
                    }
                    if (timeout) throw new HttpTimeoutException(SECRET + " https://payments-test.cashfree.com/links/synthetic");
                    return response(404, rejectedBody);
                });
                assertCode(() -> adapter(client).lookupHostedCheckout(ATTEMPT.toString()), FailureCode.UNKNOWN);
                assertThat(sent).hasSize(duringOrders ? 2 : 1).allMatch(request -> request.method().equals("GET"));
                assertThat(sent.getFirst().uri().toString()).isEqualTo("https://sandbox.cashfree.com/pg/links/" + ATTEMPT);
                if (!timeout) assertThat(rejectedBody.closed).isTrue();
            }
        }
    }

    @Test
    void lookupRejectsInvalidReferencesBeforeAnyExchangeAndLeavesObservationOnlyFetchUnchanged() throws Exception {
        HttpClient client = client();
        var adapter = adapter(client);
        for (String reference : List.of("../other", "https://evil.invalid", ATTEMPT + "?retry=true")) {
            assertCode(() -> adapter.lookupHostedCheckout(reference), FailureCode.INVALID_REQUEST);
        }
        assertCode(() -> adapter.lookupHostedCheckout("synthetic_non_uuid"), FailureCode.UNKNOWN);
        verifyNoExchange(client);
        respond(client, new ArrayList<>(), link("ACTIVE", "0.00")
                .replace(",\"link_url\":\"https://payments-test.cashfree.com/links/synthetic\"", ""), "[]");
        assertThat(adapter.fetchCheckout(ATTEMPT.toString()).status()).isEqualTo(CheckoutStatus.CREATED);
    }

    @Test
    void mapsEveryKnownCheckoutStatusWithoutInventingProviderPending() throws Exception {
        List<String> statuses = List.of("ACTIVE", "PAID", "PARTIALLY_PAID", "EXPIRED", "CANCELLED");
        List<CheckoutStatus> expected = List.of(CheckoutStatus.CREATED, CheckoutStatus.PAID,
                CheckoutStatus.PARTIALLY_PAID, CheckoutStatus.EXPIRED, CheckoutStatus.CANCELLED);
        for (int i = 0; i < statuses.size(); i++) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), link(statuses.get(i), "0.00"), "[]");
            assertThat(adapter(client).fetchCheckout(ATTEMPT.toString()).status()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void rejectsForeignDuplicateOvercountOrAlteredOrderEvidence() throws Exception {
        String tooMany = IntStream.range(0, 101).mapToObj(i -> order("synthetic_order_" + i)).collect(Collectors.joining(",", "[", "]"));
        for (String orders : List.of("[" + order(ORDER).replace(ATTEMPT.toString(), ACCOUNT.toString()) + "]",
                "[" + order(ORDER) + "," + order(ORDER) + "]", tooMany,
                "[" + order(ORDER).replace("100.00", "100.01") + "]",
                "[" + order(ORDER).replace("\"INR\"", "\"USD\"") + "]",
                "[" + order("../foreign") + "]", "{}")) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), link("ACTIVE", "0.00"), orders);
            assertCode(() -> adapter(client).fetchCheckout(ATTEMPT.toString()), FailureCode.UNKNOWN);
        }
    }

    @Test
    void exactPaymentUsesCapturedAndCompletionTimeAndDiscardsInstrumentAndCustomerDetails() throws Exception {
        HttpClient client = client();
        respond(client, new ArrayList<>(), payment("SUCCESS"));
        var result = adapter(client).fetchPayment(ORDER, PAYMENT);
        assertThat(result.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(result.captured()).isTrue();
        assertThat(result.providerPaidAt()).isEqualTo(Instant.parse(TIME));
        assertThat(result.paymentMethod()).isEqualTo("upi");
        assertThat(result.toString()).doesNotContain("synthetic-instrument-marker", "synthetic-customer-marker");
    }

    @Test
    void aFailedAttemptDoesNotPreventALaterSuccessfulAttempt() throws Exception {
        HttpClient client = client();
        respond(client, new ArrayList<>(), payment("FAILED"), payment("SUCCESS"));
        var adapter = adapter(client);
        assertThat(adapter.fetchPayment(ORDER, PAYMENT).status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(adapter.fetchPayment(ORDER, PAYMENT).status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void missingCompletionNeverFallsBackToTheAttemptTimeAndFalseCaptureStaysFalse() throws Exception {
        HttpClient client = client();
        respond(client, new ArrayList<>(), payment("SUCCESS").replace("\"payment_completion_time\":\"" + TIME + "\"", "\"payment_completion_time\":null")
                .replace("\"is_captured\":true", "\"is_captured\":false"));
        var result = adapter(client).fetchPayment(ORDER, PAYMENT);
        assertThat(result.captured()).isFalse();
        assertThat(result.providerPaidAt()).isNull();
    }

    @Test
    void rejectsMismatchedPaymentIdentityCurrencyMoneyStatusAndCaptureType() throws Exception {
        for (String wire : List.of(payment("SUCCESS").replace(ORDER, "synthetic_foreign_order"),
                payment("SUCCESS").replace(PAYMENT, "9999999999999999999"),
                payment("SUCCESS").replace("\"INR\"", "\"USD\""),
                payment("SUCCESS").replace("100.00", "-100.00"),
                payment("SUCCESS").replace("100.00", "100.001"),
                payment("SUCCESS").replace("100.00", "100.000"),
                payment("SUCCESS").replace("100.00", "null"), payment("UNRECOGNIZED"),
                payment("SUCCESS").replace("\"is_captured\":true", "\"is_captured\":\"true\""),
                payment("SUCCESS").replace(TIME, NOW.plusSeconds(1).toString()))) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), wire);
            assertCode(() -> adapter(client).fetchPayment(ORDER, PAYMENT), FailureCode.UNKNOWN);
        }
    }

    @Test
    void mapsAllPublishedPaymentAttemptStates() throws Exception {
        List<String> values = List.of("NOT_ATTEMPTED", "PENDING", "SUCCESS", "FAILED", "USER_DROPPED", "VOID", "CANCELLED");
        List<PaymentStatus> expected = List.of(PaymentStatus.CREATED, PaymentStatus.PENDING, PaymentStatus.SUCCESS,
                PaymentStatus.FAILED, PaymentStatus.USER_DROPPED, PaymentStatus.VOID, PaymentStatus.CANCELLED);
        for (int i = 0; i < values.size(); i++) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), payment(values.get(i)));
            assertThat(adapter(client).fetchPayment(ORDER, PAYMENT).status()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void discoversAllPaymentAttemptsWithoutAWebhookAndReturnsOnlyImmutableMinimalEvidence() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        String failed = payment("FAILED").replace(PAYMENT, "synthetic_payment_failed");
        String pending = payment("PENDING").replace(PAYMENT, "synthetic_payment_pending")
                .replace("\"is_captured\":true", "\"is_captured\":false")
                .replace("\"payment_completion_time\":\"" + TIME + "\"", "\"payment_completion_time\":null");
        respond(client, sent, "[" + failed + "," + pending + "," + payment("SUCCESS") + "]");
        List<PaymentObservation> observed = adapter(client).fetchPayments(ORDER);
        assertThat(observed).extracting(PaymentObservation::status)
                .containsExactly(PaymentStatus.FAILED, PaymentStatus.PENDING, PaymentStatus.SUCCESS);
        assertThat(observed).extracting(PaymentObservation::paymentReference)
                .containsExactly("synthetic_payment_failed", "synthetic_payment_pending", PAYMENT);
        assertThat(observed).allMatch(item -> item.orderReference().equals(ORDER)
                && item.currencyCode().equals("INR") && item.amount().compareTo(AMOUNT) == 0);
        assertThat(observed.get(1).captured()).isFalse();
        assertThat(observed.get(1).providerPaidAt()).isNull();
        assertThat(observed.get(2).captured()).isTrue();
        assertThat(observed.get(2).providerPaidAt()).isEqualTo(Instant.parse(TIME));
        assertThat(observed.toString()).doesNotContain("synthetic-instrument-marker", "synthetic-customer-marker");
        assertThatThrownBy(observed::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().uri().toString()).isEqualTo("https://sandbox.cashfree.com/pg/orders/" + ORDER + "/payments");
        assertThat(sent.getFirst().method()).isEqualTo("GET");
        assertThat(sent.getFirst().headers().firstValue("x-idempotency-key")).isEmpty();
    }

    @Test
    void discoveryAcceptsAnEmptyListOrExactlyOneHundredAttemptsWithoutTruncation() throws Exception {
        HttpClient client = client();
        String hundred = IntStream.range(0, 100).mapToObj(i -> payment("FAILED").replace(PAYMENT, "synthetic_payment_" + i))
                .collect(Collectors.joining(",", "[", "]"));
        respond(client, new ArrayList<>(), "[]", hundred);
        var adapter = adapter(client);
        assertThat(adapter.fetchPayments(ORDER)).isEmpty();
        assertThat(adapter.fetchPayments(ORDER)).hasSize(100);
        verify(client, times(2)).send(any(HttpRequest.class), anyHandler());
    }

    @Test
    void anyDuplicateForeignOvercountOrMalformedDiscoveredPaymentInvalidatesTheEntireList() throws Exception {
        String valid = payment("SUCCESS");
        String tooMany = IntStream.range(0, 101).mapToObj(i -> valid.replace(PAYMENT, "synthetic_payment_" + i))
                .collect(Collectors.joining(",", "[", "]"));
        List<String> malformed = List.of(valid.replace(ORDER, "synthetic_foreign_order"),
                valid.replace(PAYMENT, "../foreign"), valid.replace("\"INR\"", "\"USD\""),
                valid.replace("100.00", "100.001"), valid.replace("100.00", "-1.00"),
                valid.replace("100.00", "100000000.01"), valid.replace("100.00", "null"),
                valid.replace("\"is_captured\":true", "\"is_captured\":\"true\""),
                valid.replace(TIME, NOW.plusSeconds(1).toString()), valid.replace(TIME, "invalid-time"),
                payment("UNRECOGNIZED"), valid.replace("\"cf_payment_id\":\"" + PAYMENT + "\",", ""),
                valid.replace("\"payment_status\":\"SUCCESS\"", "\"payment_status\":\"FAILED\",\"payment_status\":\"SUCCESS\""),
                "null", "[]");
        for (String item : malformed) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), "[" + valid + "," + item + "]");
            assertCode(() -> adapter(client).fetchPayments(ORDER), FailureCode.UNKNOWN);
            verify(client, times(1)).send(any(HttpRequest.class), anyHandler());
        }
        for (String list : List.of("[" + valid + "," + valid + "]", tooMany, "{}", "null", "[] {}", "{invalid",
                "[" + valid + "," + valid.replace("\"" + PAYMENT + "\"", PAYMENT) + "]")) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), list);
            assertCode(() -> adapter(client).fetchPayments(ORDER), FailureCode.UNKNOWN);
        }
    }

    @Test
    void cancellationUsesAnEmptyPostAndStableDistinctPersistedIdentityThenRefreshesExactLinkAndAllOrders() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        respond(client, sent, link("CANCELLED", "0.00"), link("CANCELLED", "0.00"), "[" + order(ORDER) + "]",
                link("CANCELLED", "0.00"), link("CANCELLED", "0.00"), "[" + order(ORDER) + "]");
        var adapter = adapter(client);
        var request = new CancellationRequest(CANCELLATION, ATTEMPT.toString());
        var first = adapter.cancelCheckout(request);
        assertThat(first).isEqualTo(adapter.cancelCheckout(request));
        assertThat(first.status()).isEqualTo(CheckoutStatus.CANCELLED);
        assertThat(first.orderReferences()).containsExactly(ORDER);
        assertThat(sent).hasSize(6);
        for (int offset : List.of(0, 3)) {
            HttpRequest mutation = sent.get(offset);
            assertThat(mutation.method()).isEqualTo("POST");
            assertThat(mutation.uri().toString()).isEqualTo("https://sandbox.cashfree.com/pg/links/" + ATTEMPT + "/cancel");
            assertThat(mutation.headers().firstValue("x-idempotency-key")).contains(CANCELLATION.toString());
            assertThat(mutation.headers().firstValue("x-idempotency-key").orElseThrow()).isNotEqualTo(ATTEMPT.toString());
            assertThat(mutation.headers().firstValue("Content-Type")).isEmpty();
            assertThat(mutation.headers().firstValue("x-api-version")).contains("2026-01-01");
            assertThat(mutation.timeout()).contains(Duration.ofSeconds(20));
            assertThat(publishedBytes(mutation)).isEmpty();
            assertThat(sent.get(offset + 1).method()).isEqualTo("GET");
            assertThat(sent.get(offset + 1).uri().toString()).endsWith("/links/" + ATTEMPT);
            assertThat(sent.get(offset + 2).uri().toString()).endsWith("/links/" + ATTEMPT + "/orders?status=ALL");
            assertThat(sent.get(offset + 1).headers().firstValue("x-idempotency-key")).isEmpty();
        }
    }

    @Test
    void cancellationNeverInfersClosureFromTheOfficialActiveExampleOrFromAnUnconfirmedMutationStatus() throws Exception {
        for (String mutationStatus : List.of("ACTIVE", "CANCELLED")) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), link(mutationStatus, "0.00"), link("ACTIVE", "0.00"), "[]");
            assertThat(adapter(client).cancelCheckout(new CancellationRequest(CANCELLATION, ATTEMPT.toString())).status())
                    .isEqualTo(CheckoutStatus.CREATED);
        }
        HttpClient client = client();
        respond(client, new ArrayList<>(), link("ACTIVE", "0.00"), link("CANCELLED", "0.00"), "[]");
        assertThat(adapter(client).cancelCheckout(new CancellationRequest(CANCELLATION, ATTEMPT.toString())).status())
                .isEqualTo(CheckoutStatus.CANCELLED);
    }

    @Test
    void cancellationRacingWithPaymentReturnsTheRefreshedPaidObservationNotInventedCancellation() throws Exception {
        for (String mutationStatus : List.of("ACTIVE", "PAID")) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), link(mutationStatus, mutationStatus.equals("PAID") ? "100.00" : "0.00"),
                    link("PAID", "100.00"), "[" + order(ORDER) + "]", "[" + payment("SUCCESS") + "]");
            var adapter = adapter(client);
            var observed = adapter.cancelCheckout(new CancellationRequest(CANCELLATION, ATTEMPT.toString()));
            assertThat(observed.status()).isEqualTo(CheckoutStatus.PAID);
            assertThat(observed.amountPaid()).isEqualByComparingTo(AMOUNT);
            assertThat(adapter.fetchPayments(observed.orderReferences().getFirst()).getFirst().status()).isEqualTo(PaymentStatus.SUCCESS);
        }
    }

    @Test
    void providerExpiryDoesNotTurnAnExistingPendingPaymentIntoFailureOrReleaseEvidence() throws Exception {
        HttpClient client = client();
        String pending = payment("PENDING").replace("\"is_captured\":true", "\"is_captured\":false")
                .replace("\"payment_completion_time\":\"" + TIME + "\"", "\"payment_completion_time\":null");
        respond(client, new ArrayList<>(), link("EXPIRED", "0.00").replace(EXPIRY, NOW.minusSeconds(1).toString()),
                "[" + order(ORDER) + "]", "[" + pending + "]");
        var adapter = adapter(client);
        var checkout = adapter.fetchCheckout(ATTEMPT.toString());
        assertThat(checkout.status()).isEqualTo(CheckoutStatus.EXPIRED);
        var discovered = adapter.fetchPayments(checkout.orderReferences().getFirst()).getFirst();
        assertThat(discovered.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(discovered.captured()).isFalse();
        assertThat(discovered.providerPaidAt()).isNull();
    }

    @Test
    void cancellationRejectsMalformedMutationEvidenceBeforeAnyFollowupOrRetry() throws Exception {
        String valid = link("CANCELLED", "0.00");
        for (String body : List.of(valid.replace(ATTEMPT.toString(), ACCOUNT.toString()),
                valid.replace("\"INR\"", "\"USD\""), valid.replace("100.00", "100.001"),
                valid.replace("100.00", "-100.00"), valid.replace("\"link_amount_paid\":0.00", "\"link_amount_paid\":101.00"),
                valid.replace(EXPIRY, "invalid-time"), link("UNRECOGNIZED", "0.00"), "[" + valid + "]", "null")) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), body);
            assertCode(() -> adapter(client).cancelCheckout(new CancellationRequest(CANCELLATION, ATTEMPT.toString())), FailureCode.UNKNOWN);
            verify(client, times(1)).send(any(HttpRequest.class), anyHandler());
        }
    }

    @Test
    void cancellationRejectsChangedAmountExpiryForeignLinkAndForeignOrderDuringRefresh() throws Exception {
        for (String refreshed : List.of(link("CANCELLED", "0.00").replace("100.00", "100.01"),
                link("CANCELLED", "0.00").replace(EXPIRY, NOW.plusSeconds(7200).toString()),
                link("CANCELLED", "0.00").replace(ATTEMPT.toString(), ACCOUNT.toString()))) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), link("CANCELLED", "0.00"), refreshed, "[]");
            assertCode(() -> adapter(client).cancelCheckout(new CancellationRequest(CANCELLATION, ATTEMPT.toString())), FailureCode.UNKNOWN);
        }
        HttpClient client = client();
        respond(client, new ArrayList<>(), link("CANCELLED", "0.00"), link("CANCELLED", "0.00"),
                "[" + order(ORDER).replace(ATTEMPT.toString(), ACCOUNT.toString()) + "]");
        assertCode(() -> adapter(client).cancelCheckout(new CancellationRequest(CANCELLATION, ATTEMPT.toString())), FailureCode.UNKNOWN);
        verify(client, times(3)).send(any(HttpRequest.class), anyHandler());
    }

    @Test
    void cancellationRequiresADistinctPersistedCommandBeforeHttp() {
        HttpClient client = client();
        var adapter = adapter(client);
        assertCode(() -> adapter.cancelCheckout(null), FailureCode.INVALID_REQUEST);
        assertCode(() -> adapter.cancelCheckout(new CancellationRequest(ATTEMPT, ATTEMPT.toString())), FailureCode.INVALID_REQUEST);
        verifyNoExchange(client);
    }

    @Test
    void discoveryAndCancellationTimeoutsStayUnknownAndDoNotAutomaticallyRetry() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            HttpClient client = client();
            when(client.send(any(HttpRequest.class), anyHandler())).thenThrow(new HttpTimeoutException(SECRET));
            assertCode(() -> {
                if (cancel) adapter(client).cancelCheckout(new CancellationRequest(CANCELLATION, ATTEMPT.toString()));
                else adapter(client).fetchPayments(ORDER);
            }, FailureCode.UNKNOWN);
            verify(client, times(1)).send(any(HttpRequest.class), anyHandler());
        }
    }

    @Test
    void refundRetriesReuseDurableNativeIdentityAndExactAmountWithoutSelectingAnotherPayment() throws Exception {
        HttpClient client = client();
        List<HttpRequest> sent = new ArrayList<>();
        respond(client, sent, refund("PENDING"), refund("PENDING"));
        var adapter = adapter(client);
        var request = new RefundRequest(REFUND, ORDER, PAYMENT, AMOUNT);
        assertThat(adapter.requestRefund(request)).isEqualTo(adapter.requestRefund(request));
        for (HttpRequest wire : sent) {
            assertThat(wire.uri().toString()).endsWith("/orders/" + ORDER + "/refunds");
            assertThat(wire.headers().firstValue("x-idempotency-key")).contains(REFUND.toString());
            JsonNode body = JSON.readTree(publishedBytes(wire));
            assertThat(body.get("refund_id").asString()).isEqualTo(REFUND.toString());
            assertThat(body.get("refund_amount").asDecimal()).isEqualByComparingTo("100.00");
            assertThat(body.has("cf_payment_id")).isFalse();
        }
        assertThat(publishedBytes(sent.getFirst())).isEqualTo(publishedBytes(sent.getLast()));
    }

    @Test
    void specificRefundBindsItsReferencesCurrencyAndProcessedTime() throws Exception {
        HttpClient client = client();
        respond(client, new ArrayList<>(), refund("SUCCESS"));
        var result = adapter(client).fetchRefund(ORDER, REFUND.toString());
        assertThat(result.refundReference()).isEqualTo(REFUND.toString());
        assertThat(result.paymentReference()).isEqualTo(PAYMENT);
        assertThat(result.currencyCode()).isEqualTo("INR");
        assertThat(result.processedAt()).isEqualTo(Instant.parse(TIME));
    }

    @Test
    void mapsRefundApprovalHoldRejectionCancellationAndSuccessWithoutInventingRefundedMoney() throws Exception {
        List<String> values = List.of("PENDING", "PENDING_APPROVAL", "ONHOLD", "REJECTED", "CANCELLED", "SUCCESS");
        List<RefundStatus> expected = List.of(RefundStatus.PENDING, RefundStatus.PENDING, RefundStatus.ON_HOLD,
                RefundStatus.FAILED, RefundStatus.CANCELLED, RefundStatus.SUCCESS);
        for (int i = 0; i < values.size(); i++) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), refund(values.get(i)));
            assertThat(adapter(client).fetchRefund(ORDER, REFUND.toString()).status()).isEqualTo(expected.get(i));
        }
    }

    @Test
    void unexpectedRefundArrayOrMismatchedPaymentAmountCurrencyRemainsUnknownWithoutAutomaticRetry() throws Exception {
        for (String wire : List.of("[" + refund("SUCCESS") + "]", refund("SUCCESS").replace(ORDER, "foreign_order"),
                refund("SUCCESS").replace(REFUND.toString(), ACCOUNT.toString()),
                refund("SUCCESS").replace(PAYMENT, "9999999999999999999"),
                refund("SUCCESS").replace("100.00", "100.01"),
                refund("SUCCESS").replace("\"INR\"", "\"USD\""), refund("ACTIVE"))) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), wire);
            assertCode(() -> adapter(client).requestRefund(new RefundRequest(REFUND, ORDER, PAYMENT, AMOUNT)), FailureCode.UNKNOWN);
            verify(client, times(1)).send(any(HttpRequest.class), anyHandler());
        }
    }

    @Test
    void authenticatesExactRawBytesBeforeReturningOnlyMinimalRoutingEvidence() throws Exception {
        byte[] body = event("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS").getBytes(StandardCharsets.UTF_8);
        var result = adapter(client()).verifyWebhook(body, sign(body, TIMESTAMP), TIMESTAMP);
        assertThat(result.bodySha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(result.checkoutReference()).isEqualTo(ATTEMPT.toString());
        assertThat(result.orderReference()).isEqualTo(ORDER);
        assertThat(result.paymentReference()).isEqualTo(PAYMENT);
        assertThat(result.toString()).doesNotContain("synthetic-customer-marker", "synthetic-instrument-marker", SECRET);
    }

    @Test
    void tamperedRawBodySignatureAndTimestampCannotBecomeAuthenticatedEvidence() throws Exception {
        var adapter = adapter(client());
        byte[] body = event("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS").getBytes(StandardCharsets.UTF_8);
        String signature = sign(body, TIMESTAMP);
        assertCode(() -> adapter.verifyWebhook((new String(body, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8), signature, TIMESTAMP), FailureCode.UNKNOWN);
        assertCode(() -> adapter.verifyWebhook(body, signature, Long.toString(NOW.plusSeconds(1).toEpochMilli())), FailureCode.UNKNOWN);
        assertCode(() -> adapter.verifyWebhook(body, "A".repeat(43) + "=", TIMESTAMP), FailureCode.UNKNOWN);
        assertCode(() -> adapter.verifyWebhook(body, HexFormat.of().formatHex(new byte[32]), TIMESTAMP), FailureCode.UNKNOWN);
        for (String time : List.of("1902003600", "01902003600000", TIMESTAMP + " ", "+" + TIMESTAMP)) {
            assertCode(() -> adapter.verifyWebhook(body, sign(body, time), time), FailureCode.UNKNOWN);
        }
    }

    @Test
    void delayedAuthenticRetriesHaveStableBodyIdentityWithoutAnAgeCutoff() throws Exception {
        byte[] body = event("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS").getBytes(StandardCharsets.UTF_8);
        var adapter = adapter(client());
        String oldTime = "1600000000000";
        assertThat(adapter.verifyWebhook(body, sign(body, oldTime), oldTime).bodySha256())
                .isEqualTo(adapter.verifyWebhook(body, sign(body, TIMESTAMP), TIMESTAMP).bodySha256());
    }

    @Test
    void routesFailedDroppedAndLaterSuccessfulEventsIndependently() throws Exception {
        var adapter = adapter(client());
        for (String status : List.of("FAILED", "USER_DROPPED", "SUCCESS")) {
            String type = "PAYMENT_" + status + "_WEBHOOK";
            byte[] body = event(type, status).getBytes(StandardCharsets.UTF_8);
            assertThat(adapter.verifyWebhook(body, sign(body, TIMESTAMP), TIMESTAMP).eventType()).isEqualTo(type);
        }
        byte[] inconsistent = event("PAYMENT_SUCCESS_WEBHOOK", "FAILED").getBytes(StandardCharsets.UTF_8);
        assertCode(() -> adapter.verifyWebhook(inconsistent, sign(inconsistent, TIMESTAMP), TIMESTAMP), FailureCode.UNKNOWN);
    }

    @Test
    void standardAndAutoRefundEventsExposeOnlyMinimalAuthenticatedReferences() throws Exception {
        var adapter = adapter(client());
        byte[] standard = ("{\"type\":\"REFUND_STATUS_WEBHOOK\",\"data\":{\"refund\":" + refund("SUCCESS") + "}}").getBytes(StandardCharsets.UTF_8);
        assertThat(adapter.verifyWebhook(standard, sign(standard, TIMESTAMP), TIMESTAMP).refundReference()).isEqualTo(REFUND.toString());
        byte[] auto = ("{\"type\":\"AUTO_REFUND_STATUS_WEBHOOK\",\"data\":{\"auto_refund\":{\"cf_payment_id\":1234567890123456789,\"refund_status\":\"SUCCESS\"}}}").getBytes(StandardCharsets.UTF_8);
        var result = adapter.verifyWebhook(auto, sign(auto, TIMESTAMP), TIMESTAMP);
        assertThat(result.paymentReference()).isEqualTo(PAYMENT);
        assertThat(result.orderReference()).isNull();
        assertThat(result.refundReference()).isNull();
    }

    @Test
    void authenticatedLegacyLinkSettlementAndUnknownEventsHaveExplicitUnsupportedOutcome() throws Exception {
        var adapter = adapter(client());
        for (String type : List.of("PAYMENT_LINK_EVENT", "SETTLEMENT_SUCCESS", "UNRECOGNIZED_EVENT")) {
            byte[] body = ("{\"type\":\"" + type + "\",\"data\":{}}").getBytes(StandardCharsets.UTF_8);
            assertCode(() -> adapter.verifyWebhook(body, sign(body, TIMESTAMP), TIMESTAMP), FailureCode.UNSUPPORTED_EVENT);
        }
    }

    @Test
    void rejectsDuplicateKeysTrailingTokensDeepLongStringsAndUnboundedNumbers() throws Exception {
        List<String> malformed = List.of(payment("SUCCESS").replace("\"payment_status\":\"SUCCESS\"", "\"payment_status\":\"FAILED\",\"payment_status\":\"SUCCESS\""),
                payment("SUCCESS") + " {}", "[".repeat(20) + "0" + "]".repeat(20),
                payment("SUCCESS").replace("synthetic-customer-marker", "x".repeat(5000)),
                payment("SUCCESS").replace("100.00", "1".repeat(40)), "{invalid", "null");
        for (String wire : malformed) {
            HttpClient client = client();
            respond(client, new ArrayList<>(), wire);
            assertCode(() -> adapter(client).fetchPayment(ORDER, PAYMENT), FailureCode.UNKNOWN);
        }
        byte[] duplicateEvent = event("PAYMENT_SUCCESS_WEBHOOK", "SUCCESS")
                .replace("\"type\":", "\"type\":\"PAYMENT_FAILED_WEBHOOK\",\"type\":").getBytes(StandardCharsets.UTF_8);
        assertCode(() -> adapter(client()).verifyWebhook(duplicateEvent, sign(duplicateEvent, TIMESTAMP), TIMESTAMP), FailureCode.UNKNOWN);
    }

    @Test
    void boundsAndClosesResponseStreamsOnBothSuccessAndFailures() throws Exception {
        for (byte[] body : List.of(payment("SUCCESS").getBytes(StandardCharsets.UTF_8), new byte[256 * 1024 + 1], new byte[0])) {
            HttpClient client = client();
            var stream = new ClosingStream(body);
            HttpResponse<InputStream> stubResponse = response(200, stream);
            when(client.send(any(HttpRequest.class), anyHandler())).thenReturn(stubResponse);
            if (body.length > 0 && body.length < 256 * 1024) adapter(client).fetchPayment(ORDER, PAYMENT);
            else assertCode(() -> adapter(client).fetchPayment(ORDER, PAYMENT), FailureCode.UNKNOWN);
            assertThat(stream.closed).isTrue();
        }
        HttpClient client = client();
        var stream = new ClosingStream("synthetic-sensitive-body".getBytes(StandardCharsets.UTF_8));
        HttpResponse<InputStream> stubResponse = response(302, stream);
        when(client.send(any(HttpRequest.class), anyHandler())).thenReturn(stubResponse);
        assertCode(() -> adapter(client).fetchPayment(ORDER, PAYMENT), FailureCode.UNKNOWN);
        assertThat(stream.closed).isTrue();
        verify(client, times(1)).send(any(HttpRequest.class), anyHandler());
    }

    @Test
    void timeoutAndIoFailuresExposeOnlyUnknownAndNoSensitiveCause() throws Exception {
        for (IOException problem : List.of(new IOException(SECRET + " synthetic-sensitive-body"), new HttpTimeoutException(SECRET))) {
            HttpClient client = client();
            when(client.send(any(HttpRequest.class), anyHandler())).thenThrow(problem);
            assertCode(() -> adapter(client).fetchPayment(ORDER, PAYMENT), FailureCode.UNKNOWN);
            verify(client, times(1)).send(any(HttpRequest.class), anyHandler());
        }
    }

    @Test
    void aStalledBodyReadHasItsOwnDeadlineAndCancelsTheReadTask() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        InputStream stalled = new InputStream() {
            @Override public int read() throws IOException {
                try { new CountDownLatch(1).await(); return -1; }
                catch (InterruptedException exception) { released.countDown(); throw new IOException("synthetic blocked read"); }
            }
        };
        try (stalled) {
            assertThatThrownBy(() -> CashfreePaymentProviderAdapter.readBoundedBody(stalled,
                    System.nanoTime() + Duration.ofMillis(100).toNanos())).isInstanceOf(IOException.class).hasNoCause();
            assertThat(released.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void interruptionPreservesTheInterruptAndRedactsTheUnderlyingFailure() throws Exception {
        HttpClient client = client();
        when(client.send(any(HttpRequest.class), anyHandler())).thenThrow(new InterruptedException(SECRET));
        try {
            assertCode(() -> adapter(client).fetchPayment(ORDER, PAYMENT), FailureCode.UNKNOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void invalidReferencesCannotChangeApiHostPathOrQuery() {
        HttpClient client = client();
        var adapter = adapter(client);
        for (String reference : List.of("../other", "order?x=y", "https://evil.invalid", "order%2fother", "order#fragment")) {
            assertCode(() -> adapter.fetchPayment(reference, PAYMENT), FailureCode.INVALID_REQUEST);
            assertCode(() -> adapter.fetchPayments(reference), FailureCode.INVALID_REQUEST);
            assertCode(() -> adapter.fetchRefund(ORDER, reference), FailureCode.INVALID_REQUEST);
        }
        verifyNoExchange(client);
    }

    private static CashfreePaymentProviderAdapter adapter(HttpClient client) {
        return new CashfreePaymentProviderAdapter(Environment.TEST, CLIENT_ID, SECRET, ORIGIN, CLOCK, client);
    }

    private static HttpClient client() {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        return client;
    }

    private static CheckoutRequest request() { return command(Instant.parse(EXPIRY), returnUri(), "0000000000"); }
    private static URI returnUri() { return URI.create(ORIGIN + "/family/payments/" + ATTEMPT); }
    private static CheckoutRequest command(Instant expiry, URI returnUri, String phone) {
        return new CheckoutRequest(ATTEMPT, ACCOUNT, AMOUNT, "INR", expiry, returnUri, phone);
    }

    private static String link(String status, String paid) {
        return "{\"link_id\":\"" + ATTEMPT + "\",\"link_currency\":\"INR\",\"link_amount\":100.00,\"link_amount_paid\":" + paid
                + ",\"link_status\":\"" + status + "\",\"link_expiry_time\":\"" + EXPIRY
                + "\",\"link_partial_payments\":false,\"link_url\":\"https://payments-test.cashfree.com/links/synthetic\"}";
    }

    private static String order(String id) {
        return "{\"link_id\":\"" + ATTEMPT + "\",\"order_id\":\"" + id + "\",\"order_amount\":100.00,\"order_currency\":\"INR\"}";
    }

    private static String payment(String status) {
        return "{\"order_id\":\"" + ORDER + "\",\"cf_payment_id\":\"" + PAYMENT
                + "\",\"payment_amount\":100.00,\"payment_currency\":\"INR\",\"payment_status\":\"" + status
                + "\",\"is_captured\":true,\"payment_completion_time\":\"" + TIME + "\",\"payment_time\":\"" + NOW.minusSeconds(120)
                + "\",\"payment_group\":\"upi\",\"payment_method\":{\"card\":\"synthetic-instrument-marker\"},"
                + "\"customer_details\":{\"customer_name\":\"synthetic-customer-marker\"}}";
    }

    private static String refund(String status) {
        return "{\"refund_id\":\"" + REFUND + "\",\"order_id\":\"" + ORDER + "\",\"cf_payment_id\":\"" + PAYMENT
                + "\",\"refund_amount\":100.00,\"refund_currency\":\"INR\",\"refund_status\":\"" + status + "\",\"processed_at\":\"" + TIME + "\"}";
    }

    private static String event(String type, String status) {
        return "{\"type\":\"" + type + "\",\"data\":{\"order\":{\"order_id\":\"" + ORDER
                + "\",\"order_tags\":{\"link_id\":\"" + ATTEMPT + "\"}},\"payment\":" + payment(status) + "}}";
    }

    private static String sign(byte[] body, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, InputStream body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static HttpResponse.BodyHandler<InputStream> anyHandler() {
        return org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<InputStream>>any();
    }

    private static void respond(HttpClient client, List<HttpRequest> sent, String... bodies) throws Exception {
        int[] index = {0};
        when(client.send(any(HttpRequest.class), anyHandler())).thenAnswer(call -> {
            sent.add(call.getArgument(0));
            return response(200, new ClosingStream(bodies[index[0]++].getBytes(StandardCharsets.UTF_8)));
        });
    }

    private static void verifyNoExchange(HttpClient client) {
        try { verify(client, never()).send(any(HttpRequest.class), anyHandler()); }
        catch (IOException | InterruptedException exception) { throw new AssertionError(exception); }
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, FailureCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ProviderFailure.class, failure -> {
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.toString()).doesNotContain(SECRET, CLIENT_ID, "synthetic-sensitive-body", "customer_phone", "https://");
        });
    }

    private static byte[] publishedBytes(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buffer) {
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            @Override public void onError(Throwable error) { result.completeExceptionally(error); }
            @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        });
        return result.get(1, TimeUnit.SECONDS);
    }

    private static final class ClosingStream extends ByteArrayInputStream {
        private boolean closed;
        private ClosingStream(byte[] body) { super(body); }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
