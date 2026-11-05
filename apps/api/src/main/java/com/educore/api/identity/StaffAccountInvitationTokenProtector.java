package com.educore.api.identity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Separate purpose binding: existing guardian ciphertext and associated data never change. */
@Component
public class StaffAccountInvitationTokenProtector {
    private final SensitiveValueCipher cipher;
    public StaffAccountInvitationTokenProtector(SensitiveValueCipher cipher) { this.cipher = cipher; }

    public ProtectedToken protect(String rawToken, UUID organisationId, UUID schoolId, UUID invitationId) {
        var encrypted = cipher.encrypt(rawToken.getBytes(StandardCharsets.UTF_8), aad(organisationId, schoolId, invitationId));
        return new ProtectedToken(encrypted.ciphertext(), encrypted.nonce());
    }
    public String reveal(ProtectedToken protectedToken, UUID organisationId, UUID schoolId, UUID invitationId) {
        byte[] plain = cipher.decrypt(new SensitiveValueCipher.EncryptedValue(protectedToken.ciphertext(), protectedToken.nonce()),
                aad(organisationId, schoolId, invitationId));
        try { return new String(plain, StandardCharsets.UTF_8); }
        finally { java.util.Arrays.fill(plain, (byte) 0); }
    }
    private static String aad(UUID organisationId, UUID schoolId, UUID invitationId) {
        if (organisationId == null || schoolId == null || invitationId == null) throw new IllegalArgumentException("Exact staff invitation scope is required");
        return "staff-account-invitation:v1:" + organisationId + ":" + schoolId + ":" + invitationId;
    }
    public record ProtectedToken(String ciphertext, String nonce) {
        @Override public String toString() { return "StaffInvitationProtectedToken[redacted]"; }
    }
}
