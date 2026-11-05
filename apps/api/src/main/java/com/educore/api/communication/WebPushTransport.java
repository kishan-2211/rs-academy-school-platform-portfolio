package com.educore.api.communication;

import com.interaso.webpush.WebPush;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class WebPushTransport implements PushTransport {
    // RFC 8291's 4096-byte push-service limit includes the 86-byte header, delimiter and GCM tag.
    private static final int MAX_PAYLOAD_BYTES = 3993;
    private final WebPushDeliveryConfiguration configuration;
    private final Clock clock;
    private final HttpClient client;

    @Autowired
    public WebPushTransport(WebPushDeliveryConfiguration configuration, Clock clock) {
        this(configuration, clock, configuration.enabled()
                ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER).build() : null);
    }

    WebPushTransport(WebPushDeliveryConfiguration configuration, Clock clock, HttpClient client) {
        this.configuration = configuration;
        this.clock = clock;
        this.client = client;
        if (configuration.enabled() && client == null)
            throw new IllegalArgumentException("Enabled Web Push transport requires an HTTP client");
        if (client != null && client.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Web Push transport must not follow redirects");
    }

    @Override
    public Result send(Message message) {
        if (!configuration.enabled()) return result(Outcome.DISABLED, null, "PUSH_DELIVERY_DISABLED", 0);
        if (message == null || message.expiresAt() == null)
            return result(Outcome.PERMANENT_FAILURE, null, "PUSH_MESSAGE_INVALID", 0);
        Instant now = Instant.now(clock);
        if (!message.expiresAt().isAfter(now))
            return result(Outcome.PERMANENT_FAILURE, null, "PUSH_MESSAGE_EXPIRED", 0);
        URI endpoint;
        try { endpoint = configuration.endpoint(message.endpoint()); }
        catch (IllegalArgumentException exception) {
            return result(Outcome.PERMANENT_FAILURE, null, "PUSH_ENDPOINT_DENIED", 0);
        }
        HttpRequest request;
        try {
            byte[] payload = message.payload().getBytes(StandardCharsets.UTF_8);
            byte[] p256dh = Base64.getUrlDecoder().decode(message.p256dh());
            byte[] auth = Base64.getUrlDecoder().decode(message.auth());
            if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES || p256dh.length != 65
                    || p256dh[0] != 4 || auth.length != 16 || message.topic() == null
                    || !message.topic().matches("[A-Za-z0-9_-]{1,32}")) throw new IllegalArgumentException();
            int ttl = (int) Math.min(86400, Math.max(0, Duration.between(now, message.expiresAt()).getSeconds()));
            WebPush webPush = configuration.webPush();
            byte[] encrypted = webPush.getBody(payload, p256dh, auth);
            var builder = HttpRequest.newBuilder(endpoint).timeout(configuration.requestTimeout())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(encrypted));
            webPush.getHeaders(endpoint.toString(), ttl, message.topic(),
                    message.urgent() ? WebPush.Urgency.High : WebPush.Urgency.Normal).forEach(builder::header);
            request = builder.build();
        } catch (Exception exception) {
            return result(Outcome.PERMANENT_FAILURE, null, "PUSH_ENCRYPTION_INPUT_INVALID", 0);
        }
        try {
            // Stop at response headers: provider bodies may be unbounded or contain private endpoint details.
            HttpResponse<Void> response = client.send(request, info -> new HeaderOnlySubscriber());
            return classify(response.statusCode(), response.headers().firstValue("Retry-After").orElse(null), now);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return result(Outcome.RETRYABLE_FAILURE, null, "PUSH_SEND_INTERRUPTED", 60);
        } catch (IOException exception) {
            return result(Outcome.RETRYABLE_FAILURE, null, "PUSH_NETWORK_FAILURE", 60);
        } catch (RuntimeException exception) {
            return result(Outcome.RETRYABLE_FAILURE, null, "PUSH_TRANSPORT_FAILURE", 60);
        }
    }

    static Result classify(int status, String retryAfter, Instant now) {
        if (status == 200 || status == 201 || status == 202) return result(Outcome.ACCEPTED, status, "PUSH_PROVIDER_ACCEPTED", 0);
        if (status == 404 || status == 410) return result(Outcome.SUBSCRIPTION_EXPIRED, status, "PUSH_SUBSCRIPTION_EXPIRED", 0);
        if (status == 401 || status == 403) return result(Outcome.CONFIGURATION_FAILURE, status, "PUSH_PROVIDER_AUTH_REJECTED", 0);
        if (status == 408 || status == 429 || status >= 500 && status <= 599)
            return result(Outcome.RETRYABLE_FAILURE, status, "PUSH_PROVIDER_RETRYABLE", retryDelay(retryAfter, now));
        return result(Outcome.PERMANENT_FAILURE, status, "PUSH_PROVIDER_REJECTED", 0);
    }

    private static long retryDelay(String value, Instant now) {
        long seconds = 60;
        if (value != null && value.length() <= 80) {
            try {
                seconds = value.strip().matches("[0-9]{1,9}") ? Long.parseLong(value.strip())
                        : Duration.between(now, ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds();
            } catch (RuntimeException ignored) { /* Retain the safe minimum for malformed provider headers. */ }
        }
        return Math.max(60, Math.min(86400, seconds));
    }

    private static Result result(Outcome outcome, Integer status, String code, long delay) {
        return new Result(outcome, status, code, delay);
    }

    static final class HeaderOnlySubscriber implements HttpResponse.BodySubscriber<Void> {
        @Override public CompletionStage<Void> getBody() { return CompletableFuture.completedFuture(null); }
        @Override public void onSubscribe(Flow.Subscription subscription) { subscription.cancel(); }
        @Override public void onNext(List<ByteBuffer> ignored) { /* No provider body is retained. */ }
        @Override public void onError(Throwable ignored) { /* Header status is the only delivery evidence. */ }
        @Override public void onComplete() { /* No body processing. */ }
    }

    @PreDestroy
    void close() { if (client != null) client.close(); }
}
