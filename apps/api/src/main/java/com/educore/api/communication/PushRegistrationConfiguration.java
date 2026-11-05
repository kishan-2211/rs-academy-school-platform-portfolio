package com.educore.api.communication;

import jakarta.annotation.PostConstruct;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PushRegistrationConfiguration {

    private final boolean enabled;
    private final String applicationServerKey;

    public PushRegistrationConfiguration(
            @Value("${app.push.registration-enabled:false}") boolean enabled,
            @Value("${app.push.application-server-key:}") String applicationServerKey) {
        this.enabled = enabled;
        this.applicationServerKey = applicationServerKey == null ? "" : applicationServerKey.strip();
    }

    @PostConstruct
    void validate() {
        if (enabled && !isValidApplicationServerKey(applicationServerKey)) {
            throw new IllegalStateException(
                    "APP_PUSH_APPLICATION_SERVER_KEY must be an uncompressed P-256 public key when push registration is enabled");
        }
    }

    public boolean available() {
        return enabled && isValidApplicationServerKey(applicationServerKey);
    }

    public String applicationServerKey() {
        return available() ? applicationServerKey : null;
    }

    public static boolean isValidApplicationServerKey(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value.strip());
            return decoded.length == 65 && decoded[0] == 0x04;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
