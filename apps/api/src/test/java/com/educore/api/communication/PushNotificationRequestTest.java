package com.educore.api.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.communication.PushNotificationController.RegisterPushSubscriptionRequest;
import com.educore.api.communication.PushNotificationController.RevokePushSubscriptionRequest;
import jakarta.validation.Validation;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class PushNotificationRequestTest {

    @Test
    void acceptsBrowserSubscriptionMaterialAndPinsPortalAuthorization() {
        byte[] publicKey = new byte[65];
        publicKey[0] = 0x04;
        byte[] auth = new byte[16];
        String p256dh = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey);
        String authValue = Base64.getUrlEncoder().withoutPadding().encodeToString(auth);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var registration = new RegisterPushSubscriptionRequest(
                    UUID.randomUUID(), "https://push.example.org/subscriptions/synthetic",
                    p256dh, authValue, "Family phone");
            var revocation = new RevokePushSubscriptionRequest(
                    UUID.randomUUID(), "https://push.example.org/subscriptions/synthetic");

            assertThat(factory.getValidator().validate(registration)).isEmpty();
            assertThat(factory.getValidator().validate(revocation)).isEmpty();
        }
        PreAuthorize guard = PushNotificationController.class.getAnnotation(PreAuthorize.class);
        assertThat(guard).isNotNull();
        assertThat(guard.value()).contains(
                "CAPABILITY_communication.read", "CAPABILITY_portal.guardian",
                "CAPABILITY_portal.student");
    }

    @Test
    void rejectsMissingScopeAndMalformedBrowserKeys() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var registration = new RegisterPushSubscriptionRequest(
                    null, "", "not-a-browser-key", "short", "\n");
            var revocation = new RevokePushSubscriptionRequest(null, "");

            assertThat(factory.getValidator().validate(registration))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "endpoint", "p256dh", "auth", "deviceLabel");
            assertThat(factory.getValidator().validate(revocation))
                    .extracting(value -> value.getPropertyPath().toString())
                    .contains("schoolId", "endpoint");
        }
    }

    @Test
    void enablesRegistrationOnlyWithAnUncompressedP256PublicKey() {
        byte[] publicKey = new byte[65];
        publicKey[0] = 0x04;
        String valid = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey);

        assertThat(PushRegistrationConfiguration.isValidApplicationServerKey(valid)).isTrue();
        assertThat(PushRegistrationConfiguration.isValidApplicationServerKey("not-a-key")).isFalse();
        assertThatThrownBy(() -> {
            var configuration = new PushRegistrationConfiguration(true, "not-a-key");
            configuration.validate();
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_PUSH_APPLICATION_SERVER_KEY");
    }
}
