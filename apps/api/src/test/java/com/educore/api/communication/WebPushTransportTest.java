package com.educore.api.communication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.communication.PushTransport.Message;
import com.educore.api.communication.PushTransport.Outcome;
import com.interaso.webpush.VapidKeys;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

class WebPushTransportTest {
    private static final Instant NOW = Instant.parse("2030-04-10T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/synthetic-only";
    private static final String HOST = "fcm.googleapis.com";
    private static final String SUBJECT = "mailto:notifications@example.org";
    private static final VapidKeys SENDER = VapidKeys.generate();
    private static final VapidKeys BROWSER = VapidKeys.generate();
    private static final byte[] AUTH = new byte[16];
    private static final String PAYLOAD = "{\"title\":\"School update\",\"body\":\"कल अवकाश है\",\"url\":\"/portal\"}";

    private static String base64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static String privateKey(VapidKeys keys) {
        byte[] raw = keys.getPrivateKey().getS().toByteArray();
        byte[] scalar = new byte[32];
        System.arraycopy(raw, Math.max(0, raw.length - 32), scalar, Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        return base64(scalar);
    }
    private static WebPushDeliveryConfiguration config(boolean enabled) {
        return new WebPushDeliveryConfiguration(enabled, base64(SENDER.getApplicationServerKey()),
                privateKey(SENDER), SUBJECT, HOST, 10);
    }
    private static Message message(String endpoint) {
        return new Message(endpoint, base64(BROWSER.getApplicationServerKey()), base64(AUTH), PAYLOAD,
                "synthetic_notice_1", true, NOW.plusSeconds(1800));
    }
    private static HttpClient client() {
        HttpClient client = mock(HttpClient.class);
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
        return client;
    }
    @SuppressWarnings("unchecked")
    private static HttpResponse<Void> response(int status) {
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (name, value) -> true));
        return response;
    }

    @Test
    void encryptsInteroperablePayloadAndSignsVapidWithoutSendingToALiveProvider() throws Exception {
        HttpClient client = client();
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    assertThat(request.method()).isEqualTo("POST");
                    assertThat(request.uri().toString()).isEqualTo(ENDPOINT);
                    assertThat(request.timeout()).contains(java.time.Duration.ofSeconds(10));
                    assertThat(request.headers().firstValue("Content-Encoding")).contains("aes128gcm");
                    assertThat(request.headers().firstValue("TTL")).contains("1800");
                    assertThat(request.headers().firstValue("Urgency")).contains("high");
                    byte[] wire = publishedBytes(request);
                    assertThat(wire).hasSizeLessThanOrEqualTo(4096);
                    assertThat(new String(wire, StandardCharsets.UTF_8)).doesNotContain(PAYLOAD);
                    assertThat(decrypt(wire)).isEqualTo(PAYLOAD);
                    String authorization = request.headers().firstValue("Authorization").orElseThrow();
                    String token = authorization.substring("vapid t=".length(), authorization.indexOf(", k="));
                    String[] parts = token.split("\\.");
                    Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
                    verifier.initVerify(SENDER.getPublicKey());
                    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                    assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
                    String claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                    assertThat(claims).contains("https://fcm.googleapis.com", SUBJECT).doesNotContain("synthetic-only");
                    return response(201);
                });
        assertThat(new WebPushTransport(config(true), CLOCK, client).send(message(ENDPOINT)).outcome()).isEqualTo(Outcome.ACCEPTED);
    }

    @Test
    void rejectsUnapprovedDestinationsRedirectClientsExpiredMessagesAndOversizedUtf8BeforeNetworking() throws Exception {
        HttpClient client = client();
        var transport = new WebPushTransport(config(true), CLOCK, client);
        for (String endpoint : List.of("http://fcm.googleapis.com/x", "https://localhost/x", "https://127.0.0.1/x",
                "https://[::1]/x", "https://fcm.googleapis.com.evil.org/x", "https://fcm.googleapis.com:8443/x",
                "https://user@fcm.googleapis.com/x", "https://fcm.googleapis.com/x#fragment", "https://fcm.googleapis.com./x"))
            assertThat(transport.send(message(endpoint)).safeCode()).isEqualTo("PUSH_ENDPOINT_DENIED");
        var expired = new Message(ENDPOINT, "", "", PAYLOAD, "topic", false, NOW);
        assertThat(transport.send(expired).safeCode()).isEqualTo("PUSH_MESSAGE_EXPIRED");
        var oversized = new Message(ENDPOINT, base64(BROWSER.getApplicationServerKey()), base64(AUTH), "ह".repeat(1332), "topic", false, NOW.plusSeconds(60));
        assertThat(transport.send(oversized).safeCode()).isEqualTo("PUSH_ENCRYPTION_INPUT_INVALID");
        verify(client, never()).send(any(), any());
        when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
        assertThatThrownBy(() -> new WebPushTransport(config(true), CLOCK, client)).hasMessageContaining("must not follow redirects");
    }

    @Test
    void rejectsMalformedKeysAndUnsafeConfigurationWithoutEchoingSecrets() {
        assertThatThrownBy(() -> new WebPushDeliveryConfiguration(true, base64(SENDER.getApplicationServerKey()),
                privateKey(BROWSER), SUBJECT, HOST, 10)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(privateKey(BROWSER)).hasNoCause();
        for (String host : List.of("*", "*.googleapis.com", "127.0.0.1", "localhost", "service.internal", "host:443", "", "example.org,"))
            assertThatThrownBy(() -> new WebPushDeliveryConfiguration(false, "", "", "", host, 10)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new WebPushDeliveryConfiguration(false, "", "", "", HOST, 0)).hasMessageContaining("timeout");
        assertThatThrownBy(() -> new WebPushDeliveryConfiguration(true, "", "secret-marker", "http://example.org", HOST, 10))
                .hasMessageNotContaining("secret-marker").hasNoCause();
        HttpClient client = client();
        assertThat(new WebPushTransport(config(false), CLOCK, client).send(message(ENDPOINT)).outcome()).isEqualTo(Outcome.DISABLED);
        var disabled = new WebPushTransport(config(false), CLOCK);
        assertThat(disabled.send(message(ENDPOINT)).outcome()).isEqualTo(Outcome.DISABLED);
        disabled.close();
        assertThat(message(ENDPOINT).toString()).doesNotContain(ENDPOINT, PAYLOAD, base64(AUTH));
    }

    @Test
    void distinguishesAcceptanceExpiryConfigurationErrorsAndRetriesWithoutProviderBodyData() {
        for (int status : List.of(200, 201, 202)) assertThat(WebPushTransport.classify(status, null, NOW).outcome()).isEqualTo(Outcome.ACCEPTED);
        for (int status : List.of(404, 410)) assertThat(WebPushTransport.classify(status, null, NOW).outcome()).isEqualTo(Outcome.SUBSCRIPTION_EXPIRED);
        for (int status : List.of(401, 403)) assertThat(WebPushTransport.classify(status, null, NOW).outcome()).isEqualTo(Outcome.CONFIGURATION_FAILURE);
        for (int status : List.of(301, 302, 307, 400, 413)) assertThat(WebPushTransport.classify(status, null, NOW).outcome()).isEqualTo(Outcome.PERMANENT_FAILURE);
        for (int status : List.of(408, 429, 500, 503)) assertThat(WebPushTransport.classify(status, "180", NOW).retryAfterSeconds()).isEqualTo(180);
        assertThat(WebPushTransport.classify(429, "Wed, 10 Apr 2030 01:10:00 GMT", NOW).retryAfterSeconds()).isEqualTo(600);
        assertThat(WebPushTransport.classify(503, "999999999", NOW).retryAfterSeconds()).isEqualTo(86400);
        assertThat(WebPushTransport.classify(503, "malformed", NOW).retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void sanitizesNetworkErrorsAndPreservesInterruption() throws Exception {
        HttpClient client = client();
        var transport = new WebPushTransport(config(true), CLOCK, client);
        when(client.send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenThrow(new IOException("private provider endpoint " + ENDPOINT));
        assertThat(transport.send(message(ENDPOINT)).safeCode()).isEqualTo("PUSH_NETWORK_FAILURE");
        doThrow(new IllegalStateException("private endpoint " + ENDPOINT)).when(client)
                .send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
        assertThat(transport.send(message(ENDPOINT)).safeCode()).isEqualTo("PUSH_TRANSPORT_FAILURE");
        doThrow(new InterruptedException("private data")).when(client)
                .send(any(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
        try {
            assertThat(transport.send(message(ENDPOINT)).safeCode()).isEqualTo("PUSH_SEND_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test
    void cancelsProviderBodyImmediatelyInsteadOfBufferingOrWaitingForIt() throws Exception {
        var subscriber = new WebPushTransport.HeaderOnlySubscriber();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        verify(subscription).cancel();
        verify(subscription, never()).request(anyLong());
        assertThat(subscriber.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void realJdkHttpClientCompletesAtHeadersWithoutWaitingForProviderBody() throws Exception {
        // Loopback tests only the JDK body handler; the production destination guard is not disabled.
        var release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/synthetic", exchange -> {
            try {
                exchange.sendResponseHeaders(201, 0);
                exchange.getResponseBody().flush();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            } finally { exchange.close(); }
        });
        server.start();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/synthetic"))
                    .timeout(java.time.Duration.ofSeconds(2)).build();
            var response = client.sendAsync(request, info -> new WebPushTransport.HeaderOnlySubscriber()).get(3, TimeUnit.SECONDS);
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).isNull();
            assertThat(release.getCount()).isEqualTo(1);
        } finally { release.countDown(); server.stop(0); }
    }

    private static byte[] publishedBytes(HttpRequest request) throws Exception {
        var output = new ByteArrayOutputStream();
        var done = new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer item) { byte[] bytes = new byte[item.remaining()]; item.get(bytes); output.writeBytes(bytes); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(output.toByteArray()); }
        });
        return done.get(1, TimeUnit.SECONDS);
    }

    // Independent receiving-side RFC 8291 check using JCA, not the sender library's encryption helper.
    private static String decrypt(byte[] wire) throws Exception {
        byte[] salt = Arrays.copyOfRange(wire, 0, 16);
        assertThat(ByteBuffer.wrap(wire, 16, 4).getInt()).isEqualTo(4096);
        assertThat(wire[20]).isEqualTo((byte) 65);
        byte[] senderPublic = Arrays.copyOfRange(wire, 21, 86);
        ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(senderPublic, 1, 33)), new BigInteger(1, Arrays.copyOfRange(senderPublic, 33, 65)));
        ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, BROWSER.getPublicKey().getParams()));
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(BROWSER.getPrivateKey()); agreement.doPhase(key, true);
        byte[] info = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), BROWSER.getApplicationServerKey(), senderPublic);
        byte[] secret = derive(AUTH, agreement.generateSecret(), info, 32);
        byte[] cek = derive(salt, secret, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = derive(salt, secret, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] plaintext = cipher.doFinal(Arrays.copyOfRange(wire, 86, wire.length));
        assertThat(plaintext[plaintext.length - 1]).isEqualTo((byte) 2);
        return new String(plaintext, 0, plaintext.length - 1, StandardCharsets.UTF_8);
    }
    private static byte[] derive(byte[] salt, byte[] input, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256")); byte[] prk = mac.doFinal(input);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        return Arrays.copyOf(mac.doFinal(concat(info, new byte[] { 1 })), length);
    }
    private static byte[] concat(byte[]... parts) { var bytes = new ByteArrayOutputStream(); for (byte[] part : parts) bytes.writeBytes(part); return bytes.toByteArray(); }
}
