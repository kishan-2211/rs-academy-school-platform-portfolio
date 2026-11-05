package com.educore.api.identity;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class InvitationTokenProtector {

    private final SensitiveValueCipher cipher;

    public InvitationTokenProtector(SensitiveValueCipher cipher) {
        this.cipher = cipher;
    }

    public ProtectedToken protect(String rawToken, String invitationId) {
        SensitiveValueCipher.EncryptedValue protectedValue =
                cipher.encrypt(rawToken.getBytes(StandardCharsets.UTF_8), associatedData(invitationId));
        return new ProtectedToken(protectedValue.ciphertext(), protectedValue.nonce());
    }

    public String revealForDelivery(ProtectedToken protectedToken, String invitationId) {
        SensitiveValueCipher.EncryptedValue encryptedValue =
                new SensitiveValueCipher.EncryptedValue(protectedToken.ciphertext(), protectedToken.nonce());
        return new String(cipher.decrypt(encryptedValue, associatedData(invitationId)), StandardCharsets.UTF_8);
    }

    private static String associatedData(String invitationId) {
        return "guardian-portal-invitation:" + invitationId;
    }

    public record ProtectedToken(String ciphertext, String nonce) {
    }
}
