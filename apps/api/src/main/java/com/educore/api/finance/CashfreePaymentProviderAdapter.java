package com.educore.api.finance;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unactivated server-only Cashfree boundary. Observations never post fees or issue receipts.
 * Documentation fixtures do not establish merchant acceptance. In particular, the published
 * legacy link webhook has conflicting JSON/form descriptions and is deliberately unsupported.
 */
public final class CashfreePaymentProviderAdapter implements PaymentProviderAdapter,GatewaySettlementProvider {
    public enum Environment { TEST, LIVE }
    public enum FailureCode { INVALID_REQUEST, UNKNOWN, UNSUPPORTED_EVENT }

    /** No provider payload, credentials, URI query, or underlying exception is exposed. */
    public static final class ProviderFailure extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final FailureCode code;

        private ProviderFailure(FailureCode code) {
            super(switch (code) {
                case INVALID_REQUEST -> "Cashfree request requires valid persisted payment configuration";
                case UNKNOWN -> "Cashfree outcome is unknown; reconciliation is required";
                case UNSUPPORTED_EVENT -> "Authenticated Cashfree event is unsupported";
            });
            this.code = code;
        }

        public FailureCode code() { return code; }
    }

    private static final int MAX_BODY = 256 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("100000000.00");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(16)
                    .maxStringLength(4096).maxNameLength(128).maxNumberLength(32)
                    .maxDocumentLength(MAX_BODY).maxTokenCount(16384).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();

    private final Environment environment;
    private final String clientId;
    private final String secret;
    private final URI returnOrigin;
    private final Clock clock;
    private final HttpClient client;
    private final String apiBase;

    public CashfreePaymentProviderAdapter(Environment environment, String clientId, String secret,
            URI allowedReturnOrigin, Clock clock) {
        this(environment, clientId, secret, allowedReturnOrigin, clock, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build());
    }

    CashfreePaymentProviderAdapter(Environment environment, String clientId, String secret,
            URI allowedReturnOrigin, Clock clock, HttpClient client) {
        if (environment == null || clock == null || client == null
                || client.followRedirects() != HttpClient.Redirect.NEVER
                || !credential(clientId) || !credential(secret)) throw failure(FailureCode.INVALID_REQUEST);
        this.environment = environment;
        this.clientId = clientId;
        this.secret = secret;
        this.clock = clock;
        this.client = client;
        this.returnOrigin = origin(allowedReturnOrigin, environment);
        this.apiBase = environment == Environment.TEST
                ? "https://sandbox.cashfree.com/pg" : "https://api.cashfree.com/pg";
    }

    @Override public String providerCode() { return "CASHFREE"; }

    @Deprecated(forRemoval = false)
    @Override public HostedCheckout createHostedCheckout(UUID accountId, BigDecimal amount,
            String currencyCode, URI returnUri) {
        // An account is not a durable, retry-safe checkout identity.
        throw failure(FailureCode.INVALID_REQUEST);
    }

    @Override public HostedCheckout createHostedCheckout(CheckoutRequest request) {
        if (request == null || request.payerPhone() == null || !request.payerPhone().matches("[0-9]{10}")
                || !"INR".equals(request.currencyCode()) || !validAmount(request.amount(), false)
                || !request.expiresAt().isAfter(clock.instant())
                || request.expiresAt().isAfter(clock.instant().plus(Duration.ofHours(24)))) {
            throw failure(FailureCode.INVALID_REQUEST);
        }
        String reference = request.attemptId().toString();
        URI expectedReturn = URI.create(returnOrigin + "/family/payments/" + reference);
        if (!expectedReturn.equals(request.returnUri()) || expectedReturn.toASCIIString().length() > 250) {
            throw failure(FailureCode.INVALID_REQUEST);
        }
        var body = Map.of("link_id", reference, "link_amount", request.amount(), "link_currency", "INR",
                "link_purpose", "Fee payment", "customer_details", Map.of("customer_phone", request.payerPhone()),
                "link_partial_payments", false, "link_expiry_time", request.expiresAt().toString(),
                "link_auto_reminders", false, "link_notify", Map.of("send_sms", false,
                        "send_email", false, "send_whatsapp", false),
                "link_meta", Map.of("return_url", expectedReturn.toASCIIString()));
        JsonNode response = object(exchange("POST", "/links", body, request.attemptId()));
        Link link = readLink(response, reference);
        if (request.amount().compareTo(link.amount()) != 0 || !request.expiresAt().equals(link.expiresAt())
                || link.status() != CheckoutStatus.CREATED || link.paid().signum() != 0
                || bool(response, "link_partial_payments")) throw failure(FailureCode.UNKNOWN);
        return new HostedCheckout(reference, redirect(text(response, "link_url")));
    }

    @Override public CheckoutObservation fetchCheckout(String checkoutReference) {
        String reference = attemptReference(checkoutReference);
        Link link = readLink(object(exchange("GET", "/links/" + reference, null, null)), reference);
        return discoverCheckoutOrders(reference, link);
    }

    /** Read-only GET /links/{link_id}; never recreate a link after an unknown create outcome. */
    @Override public HostedCheckoutLookup lookupHostedCheckout(String checkoutReference) {
        String reference = attemptReference(checkoutReference);
        JsonNode response = object(exchange("GET", "/links/" + reference, null, null));
        Link link = readLink(response, reference);
        String address = optionalText(response, "link_url");
        Optional<HostedCheckout> hostedCheckout;
        if (address == null) {
            if (link.status() != CheckoutStatus.PAID && link.status() != CheckoutStatus.CANCELLED
                    && link.status() != CheckoutStatus.EXPIRED) throw failure(FailureCode.UNKNOWN);
            hostedCheckout = Optional.empty();
        } else {
            hostedCheckout = Optional.of(new HostedCheckout(reference, redirect(address)));
        }
        return new HostedCheckoutLookup(discoverCheckoutOrders(reference, link), hostedCheckout);
    }

    private CheckoutObservation discoverCheckoutOrders(String reference, Link link) {
        JsonNode orders = exchange("GET", "/links/" + reference + "/orders?status=ALL", null, null);
        if (!orders.isArray() || orders.size() > 100) throw failure(FailureCode.UNKNOWN);
        List<String> references = new ArrayList<>();
        var unique = new HashSet<String>();
        for (JsonNode order : orders) {
            object(order);
            if (!reference.equals(ref(order, "link_id")) || !"INR".equals(text(order, "order_currency"))
                    || link.amount().compareTo(money(order, "order_amount", false)) != 0) {
                throw failure(FailureCode.UNKNOWN);
            }
            String orderReference = ref(order, "order_id");
            if (!unique.add(orderReference)) throw failure(FailureCode.UNKNOWN);
            references.add(orderReference);
        }
        return new CheckoutObservation(reference, reference, link.amount(), "INR", link.paid(),
                link.status(), link.expiresAt(), references);
    }

    @Override public PaymentObservation fetchPayment(String orderReference, String paymentReference) {
        inputReference(orderReference);
        inputReference(paymentReference);
        JsonNode response = object(exchange("GET", "/orders/" + orderReference + "/payments/" + paymentReference, null, null));
        return readPayment(response, orderReference, paymentReference);
    }

    @Override public List<PaymentObservation> fetchPayments(String orderReference) {
        inputReference(orderReference);
        JsonNode response = exchange("GET", "/orders/" + orderReference + "/payments", null, null);
        // This published route has no pagination contract. Never truncate and claim completeness.
        if (!response.isArray() || response.size() > 100) throw failure(FailureCode.UNKNOWN);
        List<PaymentObservation> payments = new ArrayList<>();
        var unique = new HashSet<String>();
        for (JsonNode payment : response) {
            object(payment);
            String reference = ref(payment, "cf_payment_id");
            if (!unique.add(reference)) throw failure(FailureCode.UNKNOWN);
            payments.add(readPayment(payment, orderReference, reference));
        }
        return List.copyOf(payments);
    }

    @Override public CheckoutObservation cancelCheckout(CancellationRequest request) {
        if (request == null) throw failure(FailureCode.INVALID_REQUEST);
        String reference = attemptReference(request.checkoutReference());
        if (request.cancellationId().toString().equals(reference)) throw failure(FailureCode.INVALID_REQUEST);
        // Persist a distinct cancellation identity first. A timeout must not trigger an automatic retry.
        Link response = readLink(object(exchange("POST", "/links/" + reference + "/cancel",
                null, request.cancellationId())), reference);
        // The official cancellation example reports ACTIVE: 200 alone cannot prove closure.
        // Refresh exact provider state and all orders; no observation releases a school reservation.
        CheckoutObservation observed = fetchCheckout(reference);
        if (response.amount().compareTo(observed.amount()) != 0
                || !response.expiresAt().equals(observed.expiresAt())) throw failure(FailureCode.UNKNOWN);
        return observed;
    }

    private PaymentObservation readPayment(JsonNode response, String orderReference, String paymentReference) {
        if (!orderReference.equals(ref(response, "order_id")) || !paymentReference.equals(ref(response, "cf_payment_id"))) {
            throw failure(FailureCode.UNKNOWN);
        }
        String currency = currency(response, "payment_currency");
        BigDecimal amount = money(response, "payment_amount", false);
        PaymentStatus status = paymentStatus(text(response, "payment_status"));
        boolean captured = bool(response, "is_captured");
        // Never substitute payment_time (attempt initiation) for completion evidence.
        Instant paidAt = optionalTime(response, "payment_completion_time");
        String group = optionalText(response, "payment_group");
        if (group != null && !group.matches("[a-z][a-z0-9_]{0,40}")) throw failure(FailureCode.UNKNOWN);
        return new PaymentObservation(orderReference, paymentReference, amount, currency, status, captured, paidAt, group);
    }

    @Override public RefundObservation requestRefund(RefundRequest request) {
        if (request == null) throw failure(FailureCode.INVALID_REQUEST);
        inputReference(request.orderReference());
        inputReference(request.paymentReference());
        if (!validAmount(request.amount(), false)) throw failure(FailureCode.INVALID_REQUEST);
        var body = Map.of("refund_id", request.refundId().toString(), "refund_amount", request.amount(),
                "refund_note", "School approved refund", "refund_speed", "STANDARD");
        // The published POST schema is an object, although its example is an array.
        // Reject unexpected shape as UNKNOWN; do not retry a potentially successful mutation here.
        RefundObservation observation = readRefund(object(exchange("POST", "/orders/" + request.orderReference()
                + "/refunds", body, request.refundId())), request.orderReference(), request.refundId().toString());
        if (!request.paymentReference().equals(observation.paymentReference())
                || request.amount().compareTo(observation.amount()) != 0) throw failure(FailureCode.UNKNOWN);
        return observation;
    }

    @Override public RefundObservation fetchRefund(String orderReference, String refundReference) {
        inputReference(orderReference);
        inputReference(refundReference);
        return readRefund(object(exchange("GET", "/orders/" + orderReference + "/refunds/" + refundReference,
                null, null)), orderReference, refundReference);
    }

    /** These provider POST endpoints query reports; they cannot initiate or approve a transfer. */
    @Override public SettlementPage fetchSettlements(SettlementQuery query) {
        if(query==null||query.until().isAfter(clock.instant()))throw failure(FailureCode.INVALID_REQUEST);
        try {
            var page=object(exchange("POST","/settlements",Map.of("pagination",settlementPagination(query.cursor()),
                    "filters",Map.of("start_date",query.from().toString(),"end_date",query.until().toString())),query.readId()));
            var items=new ArrayList<SettlementSummary>();
            for(var item:settlementRows(page))items.add(settlementSummary(object(item)));
            return new SettlementPage(settlementNextCursor(page,query.cursor()),items);
        }catch(RuntimeException unavailable){throw failure(FailureCode.UNKNOWN);}
    }

    @Override public SettlementEventsPage fetchSettlementEvents(SettlementEventsQuery query) {
        if(query==null)throw failure(FailureCode.INVALID_REQUEST);
        try {
            var page=object(exchange("POST","/settlement/recon",Map.of("pagination",settlementPagination(query.cursor()),
                    "filters",Map.of("cf_settlement_ids",List.of(query.settlementReference()))),query.readId()));
            var items=new ArrayList<SettlementEvent>();
            for(var item:settlementRows(page))items.add(settlementEvent(object(item),query.settlementReference()));
            return new SettlementEventsPage(settlementNextCursor(page,query.cursor()),items);
        }catch(RuntimeException unavailable){throw failure(FailureCode.UNKNOWN);}
    }

    private static Map<String,Object> settlementPagination(String cursor){
        // LinkedHashMap permits an explicit first-page null without leaking the cursor to a URL/log.
        var result=new java.util.LinkedHashMap<String,Object>();result.put("limit",GatewaySettlementProvider.PAGE_SIZE);result.put("cursor",cursor);return result;
    }
    private static JsonNode settlementRows(JsonNode page){
        var rows=page.get("data");var limit=page.get("limit");
        if(rows==null||!rows.isArray()||rows.size()>GatewaySettlementProvider.PAGE_SIZE||limit==null||!limit.isIntegralNumber()
                ||!limit.canConvertToInt()||limit.intValue()<rows.size()||limit.intValue()>GatewaySettlementProvider.PAGE_SIZE)throw failure(FailureCode.UNKNOWN);
        return rows;
    }
    private static String settlementNextCursor(JsonNode page,String previous){
        if(!page.has("cursor"))throw failure(FailureCode.UNKNOWN);
        var value=page.get("cursor");
        if(value!=null&&!value.isNull()&&!value.isString())throw failure(FailureCode.UNKNOWN);
        String cursor=value==null||value.isNull()||value.stringValue().isEmpty()?null:value.stringValue();
        if(cursor!=null&&(!cursor.matches("[A-Za-z0-9+/_=.\\-]{1,4096}")||cursor.equals(previous)))throw failure(FailureCode.UNKNOWN);
        return cursor;
    }
    private SettlementSummary settlementSummary(JsonNode row){
        return new SettlementSummary(ref(row,"cf_settlement_id"),text(row,"status"),settlementTransferReference(row),
                settlementTime(row,"payment_from",false),settlementTime(row,"payment_till",false),
                settlementTime(row,"settlement_initiated_on",true),settlementTime(row,"settlement_processed_on",true),
                settlementMoney(row,"payment_amount"),settlementMoney(row,"pg_service_charge"),settlementMoney(row,"pg_service_tax"),
                settlementMoney(row,"adjustment"),settlementMoney(row,"settlement_charge"),settlementMoney(row,"settlement_tax"),
                settlementMoney(row,"split_service_charge"),settlementMoney(row,"split_service_tax"),settlementMoney(row,"vendor_commission"),
                settlementMoney(row,"amount_settled"),optionalText(row,"charge_type"),optionalText(row,"settlement_type"));
    }
    private SettlementEvent settlementEvent(JsonNode row,String expected){
        var settlement=object(row.get("settlement_details"));String reference=ref(settlement,"cf_settlement_id");
        if(!expected.equals(reference))throw failure(FailureCode.UNKNOWN);
        var event=object(row.get("event_details"));var order=optionalSettlementObject(row,"order_details");var payment=optionalSettlementObject(row,"payment_details");
        var refunds=row.get("refund_details");var references=new ArrayList<String>();
        if(refunds!=null&&!refunds.isNull()){
            if(!refunds.isArray()||refunds.size()>100)throw failure(FailureCode.UNKNOWN);
            for(var refund:refunds){String value=optionalReference(object(refund),"refund_id");if(value!=null)references.add(value);}
        }
        Instant occurred=settlementTime(event,"event_time",true);if(occurred==null)throw failure(FailureCode.UNKNOWN);
        BigDecimal amount=settlementMoney(event,"event_amount");if(amount==null)throw failure(FailureCode.UNKNOWN);
        return new SettlementEvent(reference,ref(event,"event_id"),text(event,"event_type"),text(event,"sale_type"),text(event,"event_status"),
                text(event,"event_currency"),amount,settlementMoney(event,"event_service_charge"),settlementMoney(event,"event_service_tax"),
                settlementMoney(event,"event_settlement_amount"),occurred,order==null?null:optionalReference(order,"order_id"),
                payment==null?null:optionalReference(payment,"cf_payment_id"),references,payment==null?null:optionalText(payment,"charges_currency"));
    }
    private static JsonNode optionalSettlementObject(JsonNode row,String field){var value=row.get(field);return value==null||value.isNull()?null:object(value);}
    private static String settlementTransferReference(JsonNode row){
        var value=row.get("settlement_utr");if(value==null||value.isNull()||value.isString()&&value.stringValue().isEmpty())return null;
        String result=value.isString()?value.stringValue():value.isIntegralNumber()?value.bigIntegerValue().toString():null;
        if(result==null||!result.matches("[A-Za-z0-9][A-Za-z0-9_.:/\\-]{0,119}"))throw failure(FailureCode.UNKNOWN);return result;
    }
    private static BigDecimal settlementMoney(JsonNode row,String field){
        var value=row.get(field);if(value==null||value.isNull())return null;
        if(!(value.isNumber()||value.isString()))throw failure(FailureCode.UNKNOWN);
        String wire=value.isString()?value.stringValue():value.toString();
        if(wire.length()>32||!wire.matches("-?(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,8})?"))throw failure(FailureCode.UNKNOWN);
        try{var amount=new BigDecimal(wire);if(amount.abs().compareTo(MAX_AMOUNT)>0)throw failure(FailureCode.UNKNOWN);return amount;}
        catch(NumberFormatException invalid){throw failure(FailureCode.UNKNOWN);}
    }
    private Instant settlementTime(JsonNode row,String field,boolean occurred){
        var value=row.get(field);if(value==null||value.isNull())return null;
        if(!value.isString())throw failure(FailureCode.UNKNOWN);String wire=value.stringValue();if(wire.isEmpty())return null;
        if(wire.length()>45)throw failure(FailureCode.UNKNOWN);Instant result=time(wire);
        if(result.isBefore(Instant.parse("2000-01-01T00:00:00Z"))||result.isAfter(Instant.parse("2100-01-01T00:00:00Z"))
                ||occurred&&result.isAfter(clock.instant()))throw failure(FailureCode.UNKNOWN);
        return result;
    }

    @Override public VerifiedWebhook verifyWebhook(byte[] rawBody, String signature, String timestamp) {
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY || signature == null
                || !signature.matches("[A-Za-z0-9+/]{43}=") || timestamp == null
                || !timestamp.matches("[1-9][0-9]{12}")) throw failure(FailureCode.UNKNOWN);
        // Snapshot caller-owned bytes before both authentication and parsing.
        byte[] payload = rawBody.clone();
        String bodyHash;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(mac.doFinal(payload), Base64.getDecoder().decode(signature))) {
                throw failure(FailureCode.UNKNOWN);
            }
            bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw failure(FailureCode.UNKNOWN);
        }
        // No age cutoff: delayed authentic retries still need durable de-duplication/reconciliation.
        JsonNode root = object(parse(payload));
        String type = text(root, "type");
        JsonNode data = object(root.get("data"));
        if (type.equals("PAYMENT_SUCCESS_WEBHOOK") || type.equals("PAYMENT_FAILED_WEBHOOK")
                || type.equals("PAYMENT_USER_DROPPED_WEBHOOK")) {
            JsonNode order = object(data.get("order"));
            JsonNode payment = object(data.get("payment"));
            String expectedStatus = switch (type) {
                case "PAYMENT_SUCCESS_WEBHOOK" -> "SUCCESS";
                case "PAYMENT_FAILED_WEBHOOK" -> "FAILED";
                default -> "USER_DROPPED";
            };
            if (!expectedStatus.equals(text(payment, "payment_status"))) throw failure(FailureCode.UNKNOWN);
            String checkout = null;
            JsonNode tags = order.get("order_tags");
            if (tags != null && !tags.isNull()) {
                object(tags);
                if (tags.hasNonNull("link_id")) checkout = attemptReference(ref(tags, "link_id"));
            }
            return new VerifiedWebhook(providerCode(), bodyHash, type, checkout, ref(order, "order_id"),
                    ref(payment, "cf_payment_id"), null);
        }
        if (type.equals("REFUND_STATUS_WEBHOOK") || type.equals("AUTO_REFUND_STATUS_WEBHOOK")) {
            JsonNode refund = object(data.get(type.equals("REFUND_STATUS_WEBHOOK") ? "refund" : "auto_refund"));
            refundStatus(text(refund, "refund_status"));
            String order = optionalReference(refund, "order_id");
            String refundReference = optionalReference(refund, "refund_id");
            if (type.equals("REFUND_STATUS_WEBHOOK") && (order == null || refundReference == null)) {
                throw failure(FailureCode.UNKNOWN);
            }
            return new VerifiedWebhook(providerCode(), bodyHash, type, null, order,
                    ref(refund, "cf_payment_id"), refundReference);
        }
        // Includes legacy PAYMENT_LINK_EVENT and settlement aggregates: neither confirms a fee payment.
        throw failure(FailureCode.UNSUPPORTED_EVENT);
    }

    private JsonNode exchange(String method, String path, Object body, UUID idempotency) {
        try {
            long deadline = System.nanoTime() + REQUEST_TIMEOUT.toNanos();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(REQUEST_TIMEOUT)
                    .header("x-api-version", "2026-01-01").header("x-client-id", clientId)
                    .header("x-client-secret", secret).header("Accept", "application/json");
            if (method.equals("GET") && body == null && idempotency == null) builder.GET();
            else if (method.equals("POST") && idempotency != null) {
                builder.header("x-idempotency-key", idempotency.toString());
                if (body == null) builder.POST(HttpRequest.BodyPublishers.noBody());
                else builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            } else throw failure(FailureCode.UNKNOWN);
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() != 200 || stream == null) throw failure(FailureCode.UNKNOWN);
                byte[] bytes = readBoundedBody(stream, deadline);
                if (bytes.length == 0 || bytes.length > MAX_BODY) throw failure(FailureCode.UNKNOWN);
                return parse(bytes);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(FailureCode.UNKNOWN);
        } catch (IOException | RuntimeException exception) {
            throw failure(FailureCode.UNKNOWN);
        }
    }

    /** The JDK streaming handler completes at headers; explicitly bound the remaining body read too. */
    static byte[] readBoundedBody(InputStream stream, long deadline) throws IOException, InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Provider response read timed out");
        FutureTask<byte[]> task = new FutureTask<>(() -> stream.readNBytes(MAX_BODY + 1));
        Thread.ofVirtual().name("cashfree-response-read").start(task);
        try { return task.get(remaining, TimeUnit.NANOSECONDS); }
        catch (ExecutionException | TimeoutException exception) {
            // The public failure deliberately never retains this task's provider-sensitive cause.
            throw new IOException("Provider response read failed");
        } finally {
            if (!task.isDone()) task.cancel(true);
        }
    }

    private static JsonNode parse(byte[] bytes) {
        try { return JSON.readTree(bytes); }
        catch (RuntimeException exception) { throw failure(FailureCode.UNKNOWN); }
    }

    private static Link readLink(JsonNode response, String reference) {
        if (!reference.equals(ref(response, "link_id"))) throw failure(FailureCode.UNKNOWN);
        currency(response, "link_currency");
        BigDecimal amount = money(response, "link_amount", false);
        BigDecimal paid = money(response, "link_amount_paid", true);
        if (paid.compareTo(amount) > 0) throw failure(FailureCode.UNKNOWN);
        CheckoutStatus status = switch (text(response, "link_status")) {
            case "ACTIVE" -> CheckoutStatus.CREATED;
            case "PAID" -> CheckoutStatus.PAID;
            case "PARTIALLY_PAID" -> CheckoutStatus.PARTIALLY_PAID;
            case "EXPIRED" -> CheckoutStatus.EXPIRED;
            case "CANCELLED" -> CheckoutStatus.CANCELLED;
            default -> throw failure(FailureCode.UNKNOWN);
        };
        return new Link(amount, paid, status, time(text(response, "link_expiry_time")));
    }

    private RefundObservation readRefund(JsonNode response, String order, String refund) {
        if (!order.equals(ref(response, "order_id")) || !refund.equals(ref(response, "refund_id"))) {
            throw failure(FailureCode.UNKNOWN);
        }
        return new RefundObservation(refund, order, ref(response, "cf_payment_id"), money(response, "refund_amount", false),
                currency(response, "refund_currency"), refundStatus(text(response, "refund_status")),
                optionalTime(response, "processed_at"));
    }

    private static PaymentStatus paymentStatus(String status) {
        return switch (status) {
            case "NOT_ATTEMPTED" -> PaymentStatus.CREATED;
            case "PENDING" -> PaymentStatus.PENDING;
            case "SUCCESS" -> PaymentStatus.SUCCESS;
            case "FAILED" -> PaymentStatus.FAILED;
            case "USER_DROPPED" -> PaymentStatus.USER_DROPPED;
            case "CANCELLED" -> PaymentStatus.CANCELLED;
            case "VOID" -> PaymentStatus.VOID;
            default -> throw failure(FailureCode.UNKNOWN);
        };
    }

    private static RefundStatus refundStatus(String status) {
        return switch (status) {
            case "PENDING", "PENDING_APPROVAL" -> RefundStatus.PENDING;
            case "SUCCESS" -> RefundStatus.SUCCESS;
            case "REJECTED" -> RefundStatus.FAILED;
            case "CANCELLED" -> RefundStatus.CANCELLED;
            case "ONHOLD" -> RefundStatus.ON_HOLD;
            default -> throw failure(FailureCode.UNKNOWN);
        };
    }

    private URI redirect(String value) {
        try {
            URI uri = URI.create(value);
            String host = environment == Environment.TEST ? "payments-test.cashfree.com" : "payments.cashfree.com";
            if (!"https".equals(uri.getScheme()) || !host.equals(uri.getHost()) || uri.getPort() != -1
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/links/[A-Za-z0-9_-]{1,100}")) {
                throw failure(FailureCode.UNKNOWN);
            }
            return uri;
        } catch (IllegalArgumentException exception) { throw failure(FailureCode.UNKNOWN); }
    }

    private static URI origin(URI uri, Environment environment) {
        if (uri == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() < -1 || uri.getPort() == 0
                || uri.getPort() > 65535 || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
            throw failure(FailureCode.INVALID_REQUEST);
        }
        boolean loopback = List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !(environment == Environment.TEST && loopback
                && "http".equals(uri.getScheme()))) throw failure(FailureCode.INVALID_REQUEST);
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    private static String inputReference(String value) {
        try { return PaymentProviderAdapter.requireReference(value); }
        catch (IllegalArgumentException exception) { throw failure(FailureCode.INVALID_REQUEST); }
    }

    private static String attemptReference(String value) {
        inputReference(value);
        try {
            if (!UUID.fromString(value).toString().equals(value)) throw failure(FailureCode.UNKNOWN);
            return value;
        } catch (IllegalArgumentException exception) { throw failure(FailureCode.UNKNOWN); }
    }

    private static JsonNode object(JsonNode node) {
        if (node == null || !node.isObject()) throw failure(FailureCode.UNKNOWN);
        return node;
    }

    private static String text(JsonNode node, String field) {
        String result = optionalText(node, field);
        if (result == null) throw failure(FailureCode.UNKNOWN);
        return result;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString() || value.stringValue().isEmpty() || value.stringValue().length() > 4096) {
            throw failure(FailureCode.UNKNOWN);
        }
        return value.stringValue();
    }

    private static String ref(JsonNode node, String field) {
        String value = optionalReference(node, field);
        if (value == null) throw failure(FailureCode.UNKNOWN);
        return value;
    }

    private static String optionalReference(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String result = value.isString() ? value.stringValue()
                : value.isIntegralNumber() ? value.bigIntegerValue().toString() : null;
        if (result == null || !result.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) throw failure(FailureCode.UNKNOWN);
        return result;
    }

    private static String currency(JsonNode node, String field) {
        if (!"INR".equals(text(node, field))) throw failure(FailureCode.UNKNOWN);
        return "INR";
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) throw failure(FailureCode.UNKNOWN);
        return value.booleanValue();
    }

    private static BigDecimal money(JsonNode node, String field, boolean zeroAllowed) {
        JsonNode value = node.get(field);
        if (value == null || !(value.isNumber() || value.isString())) throw failure(FailureCode.UNKNOWN);
        try {
            String wire = value.isString() ? value.stringValue() : value.toString();
            if (wire.length() > 32 || !wire.matches("(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,2})?")) {
                throw failure(FailureCode.UNKNOWN);
            }
            BigDecimal amount = new BigDecimal(wire);
            if (!validAmount(amount, zeroAllowed)) throw failure(FailureCode.UNKNOWN);
            return amount;
        } catch (NumberFormatException exception) { throw failure(FailureCode.UNKNOWN); }
    }

    private static boolean validAmount(BigDecimal amount, boolean zeroAllowed) {
        return amount != null && amount.scale() <= 2 && amount.signum() >= (zeroAllowed ? 0 : 1)
                && amount.compareTo(MAX_AMOUNT) <= 0;
    }

    private Instant optionalTime(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) return null;
        Instant result = time(value);
        if (result.isAfter(clock.instant())) throw failure(FailureCode.UNKNOWN);
        return result;
    }

    private static Instant time(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (RuntimeException exception) { throw failure(FailureCode.UNKNOWN); }
    }

    private static boolean credential(String value) {
        return value != null && !value.isBlank() && value.length() <= 512 && value.chars().noneMatch(c -> c < 32 || c == 127);
    }

    private static ProviderFailure failure(FailureCode code) { return new ProviderFailure(code); }
    private record Link(BigDecimal amount, BigDecimal paid, CheckoutStatus status, Instant expiresAt) {}
}
