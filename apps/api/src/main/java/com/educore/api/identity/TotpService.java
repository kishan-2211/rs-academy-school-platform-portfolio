package com.educore.api.identity;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class TotpService {

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final long STEP_SECONDS = 30;
    private final SecureRandom secureRandom;

    public TotpService(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    GeneratedSecret generateSecret() {
        byte[] value = new byte[20];
        secureRandom.nextBytes(value);
        return new GeneratedSecret(value, encodeBase32(value));
    }

    OptionalLong matchingTimeStep(byte[] secret, String suppliedCode, Instant now) {
        if (suppliedCode == null || !suppliedCode.matches("\\d{6}")) return OptionalLong.empty();
        long currentStep = Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
        for (long candidateStep = currentStep - 1; candidateStep <= currentStep + 1; candidateStep += 1) {
            byte[] expected = code(secret, candidateStep).getBytes(StandardCharsets.US_ASCII);
            byte[] supplied = suppliedCode.getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, supplied)) return OptionalLong.of(candidateStep);
        }
        return OptionalLong.empty();
    }

    String provisioningUri(String issuer, String accountName, String encodedSecret) {
        String safeIssuer = issuer == null || issuer.isBlank() ? "EduCore" : issuer.strip();
        String label = urlEncode(safeIssuer + ":" + accountName.toLowerCase(Locale.ROOT));
        return "otpauth://totp/" + label
                + "?secret=" + encodedSecret
                + "&issuer=" + urlEncode(safeIssuer)
                + "&algorithm=SHA1&digits=6&period=" + STEP_SECONDS;
    }

    private String code(byte[] secret, long timeStep) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array());
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("TOTP is unavailable", exception);
        }
    }

    static String encodeBase32(byte[] value) {
        StringBuilder encoded = new StringBuilder((value.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte current : value) {
            buffer = (buffer << 8) | (current & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                encoded.append(BASE32[(buffer >> (bitsLeft - 5)) & 0x1f]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) encoded.append(BASE32[(buffer << (5 - bitsLeft)) & 0x1f]);
        return encoded.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    record GeneratedSecret(byte[] bytes, String encoded) {
    }
}
