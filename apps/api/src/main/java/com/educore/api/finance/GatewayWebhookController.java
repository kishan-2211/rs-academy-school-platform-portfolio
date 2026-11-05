package com.educore.api.finance;

import com.educore.api.common.api.GatewayWebhookRequestPath;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Raw-byte authenticated intake only: no JSON DTO, payment posting or browser success authority. */
@RestController
public final class GatewayWebhookController {
    private final GatewayWebhookIntake intake;

    public GatewayWebhookController(GatewayWebhookIntake intake) {
        this.intake = Objects.requireNonNull(intake);
    }

    @PostMapping(GatewayWebhookRequestPath.PREFIX + "{routeKey}")
    public ResponseEntity<Void> accept(@PathVariable String routeKey, HttpServletRequest request) {
        // Reject raw encoded/extra path variants even if MVC decoded a variable.
        if (!GatewayWebhookRequestPath.matches(request)
                || !request.getRequestURI().equals(request.getContextPath() + GatewayWebhookRequestPath.PREFIX + routeKey)) {
            return empty(HttpStatus.NOT_FOUND);
        }
        String signature = singleHeader(request, "x-webhook-signature"), timestamp = singleHeader(request, "x-webhook-timestamp");
        if (!canonicalSignature(signature) || timestamp == null || !timestamp.matches("[1-9][0-9]{12}")) return empty(HttpStatus.BAD_REQUEST);
        if (!jsonContentType(singleHeader(request, "Content-Type")) || !uncompressed(request)) return empty(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        long declared = request.getContentLengthLong();
        if (declared > GatewayWebhookIntake.MAX_RAW_BODY_BYTES) return empty(HttpStatus.PAYLOAD_TOO_LARGE);
        if (declared == 0 || declared < -1) return empty(HttpStatus.BAD_REQUEST);

        byte[] raw = null;
        try {
            // Never use @RequestBody byte[]: its converter could buffer an
            // unbounded body before this method. Bound unknown/chunked bodies
            // to MAX+1, and authenticate these exact bytes without decoding.
            raw = request.getInputStream().readNBytes(GatewayWebhookIntake.MAX_RAW_BODY_BYTES + 1);
            if (raw.length == 0) return empty(HttpStatus.BAD_REQUEST);
            if (raw.length > GatewayWebhookIntake.MAX_RAW_BODY_BYTES) return empty(HttpStatus.PAYLOAD_TOO_LARGE);
            // Byte-format validation is not JSON parsing. Do not let a JSON
            // library auto-detect UTF-16/32 despite the UTF-8 HTTP contract, or
            // silently replace malformed UTF-8. Authentication still receives
            // the unchanged original bytes before any JSON deserialization.
            if (!utf8JsonBytes(raw)) return empty(HttpStatus.BAD_REQUEST);
            var retained = intake.accept(routeKey, raw, signature, timestamp);
            if (retained == null) return retry();
            // New and duplicate valid events acknowledge the same committed
            // durable inbox, not successful payment or a school fee receipt.
            return empty(HttpStatus.NO_CONTENT);
        } catch (IOException | RuntimeException unavailable) {
            // Existing intake deliberately hides whether auth, route/key or
            // storage failed. Never falsely acknowledge or leak its diagnostics.
            return retry();
        } finally {
            if (raw != null) Arrays.fill(raw, (byte) 0);
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) return null;
        String value = headers.nextElement();
        return headers.hasMoreElements() ? null : value;
    }

    private static boolean canonicalSignature(String value) {
        if (value == null || !value.matches("[A-Za-z0-9+/]{43}=")) return false;
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            return decoded.length == 32 && Base64.getEncoder().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean jsonContentType(String value) {
        // A generic media parser can collapse repeated/conflicting parameters.
        // Accept only one optional UTF-8 declaration, without ambiguous extra
        // parameters, rather than choosing whichever charset appeared last.
        return value != null && value.length() <= 128 && value.matches(
                "(?i)[ \\t]*application/json[ \\t]*(?:;[ \\t]*charset[ \\t]*=[ \\t]*(?:UTF-8|\\\"UTF-8\\\")[ \\t]*)?");
    }

    private static boolean utf8JsonBytes(byte[] bytes) {
        // RFC 3629 byte ranges: no overlong encodings, surrogate code points,
        // out-of-range scalars or truncated continuation sequences. Raw NUL
        // is illegal JSON and also rules out no-BOM UTF-16/32 auto-detection.
        for (int index = 0; index < bytes.length;) {
            int first = Byte.toUnsignedInt(bytes[index++]);
            if (first == 0) return false;
            if (first < 0x80) continue;
            int continuations, secondMin = 0x80, secondMax = 0xbf;
            if (first >= 0xc2 && first <= 0xdf) continuations = 1;
            else if (first >= 0xe0 && first <= 0xef) {
                continuations = 2;
                if (first == 0xe0) secondMin = 0xa0;
                if (first == 0xed) secondMax = 0x9f;
            } else if (first >= 0xf0 && first <= 0xf4) {
                continuations = 3;
                if (first == 0xf0) secondMin = 0x90;
                if (first == 0xf4) secondMax = 0x8f;
            } else return false;
            if (bytes.length - index < continuations) return false;
            int second = Byte.toUnsignedInt(bytes[index++]);
            if (second < secondMin || second > secondMax) return false;
            for (int remaining = 1; remaining < continuations; remaining++) {
                int next = Byte.toUnsignedInt(bytes[index++]);
                if (next < 0x80 || next > 0xbf) return false;
            }
        }
        return true;
    }

    private static boolean uncompressed(HttpServletRequest request) {
        var headers = request.getHeaders("Content-Encoding");
        if (headers == null || !headers.hasMoreElements()) return true;
        String value = headers.nextElement();
        return !headers.hasMoreElements() && "identity".equalsIgnoreCase(value);
    }

    private static ResponseEntity<Void> empty(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }

    private static ResponseEntity<Void> retry() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore())
                .header("Retry-After", "60").build();
    }
}
