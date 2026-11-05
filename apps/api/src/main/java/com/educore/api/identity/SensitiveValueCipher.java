package com.educore.api.identity;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SensitiveValueCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final ApplicationProperties properties;
    private final SecureRandom secureRandom;

    public SensitiveValueCipher(ApplicationProperties properties, SecureRandom secureRandom) {
        this.properties = properties;
        this.secureRandom = secureRandom;
    }

    public EncryptedValue encrypt(byte[] plaintext, String associatedData) {
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new EncryptedValue(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt a sensitive value", exception);
        }
    }

    public byte[] decrypt(EncryptedValue encryptedValue, String associatedData) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = Base64.getUrlDecoder().decode(encryptedValue.nonce());
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(Base64.getUrlDecoder().decode(encryptedValue.ciphertext()));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt a sensitive value", exception);
        }
    }

    public static boolean isValidKey(String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) return false;
        try {
            return Base64.getDecoder().decode(encodedKey.strip()).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private SecretKeySpec key() {
        String encodedKey = properties.security().encryptionKey();
        if (!isValidKey(encodedKey)) {
            throw new ApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SENSITIVE_ENCRYPTION_UNAVAILABLE",
                    "Sensitive token operations are unavailable until the encryption key is configured.");
        }
        return new SecretKeySpec(Base64.getDecoder().decode(encodedKey.strip()), "AES");
    }

    public record EncryptedValue(String ciphertext, String nonce) {
    }
}
