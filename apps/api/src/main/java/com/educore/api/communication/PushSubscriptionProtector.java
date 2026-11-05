package com.educore.api.communication;

import com.educore.api.identity.SensitiveValueCipher;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PushSubscriptionProtector {

    private final SensitiveValueCipher cipher;

    public PushSubscriptionProtector(SensitiveValueCipher cipher) {
        this.cipher = cipher;
    }

    public ProtectedSubscription protect(
            UUID subscriptionId, String endpoint, String p256dh, String auth) {
        return new ProtectedSubscription(
                protect(subscriptionId, "endpoint", endpoint),
                protect(subscriptionId, "p256dh", p256dh),
                protect(subscriptionId, "auth", auth));
    }

    public String reveal(UUID subscriptionId, String part, ProtectedValue value) {
        return new String(cipher.decrypt(
                new SensitiveValueCipher.EncryptedValue(value.ciphertext(), value.nonce()),
                associatedData(subscriptionId, part)), StandardCharsets.UTF_8);
    }

    private ProtectedValue protect(UUID subscriptionId, String part, String value) {
        SensitiveValueCipher.EncryptedValue encrypted = cipher.encrypt(
                value.getBytes(StandardCharsets.UTF_8), associatedData(subscriptionId, part));
        return new ProtectedValue(encrypted.ciphertext(), encrypted.nonce());
    }

    private static String associatedData(UUID subscriptionId, String part) {
        return "communication:push-subscription:" + subscriptionId + ":" + part;
    }

    public record ProtectedSubscription(
            ProtectedValue endpoint, ProtectedValue p256dh, ProtectedValue auth) {}

    public record ProtectedValue(String ciphertext, String nonce) {}
}
