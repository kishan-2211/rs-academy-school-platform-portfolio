package com.educore.api.communication;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class WebPushDeliveryConfiguration {
    private final boolean enabled;
    private final Set<String> hosts;
    private final Duration requestTimeout;
    private final WebPush webPush;

    public WebPushDeliveryConfiguration(
            @Value("${app.push.delivery.enabled:false}") boolean enabled,
            @Value("${app.push.application-server-key:}") String publicKey,
            @Value("${app.push.delivery.private-key:}") String privateKey,
            @Value("${app.push.delivery.subject:}") String subject,
            @Value("${app.push.delivery.allowed-hosts:fcm.googleapis.com,updates.push.services.mozilla.com,web.push.apple.com}") String allowedHosts,
            @Value("${app.push.delivery.request-timeout-seconds:10}") int timeoutSeconds) {
        this.enabled = enabled;
        if (timeoutSeconds < 1 || timeoutSeconds > 30)
            throw new IllegalStateException("Web Push request timeout must be between 1 and 30 seconds");
        requestTimeout = Duration.ofSeconds(timeoutSeconds);
        hosts = Arrays.stream(allowedHosts.split(",", -1)).map(String::strip)
                .map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (hosts.isEmpty() || hosts.stream().anyMatch(host -> !validProviderHost(host)))
            throw new IllegalStateException("Web Push allowed hosts must be explicit public DNS names, without wildcards or ports");
        WebPush configured = null;
        if (enabled) {
            try {
                URI sender = URI.create(subject.strip());
                boolean validSender = ("https".equals(sender.getScheme()) && sender.getHost() != null
                        && sender.getUserInfo() == null && sender.getFragment() == null)
                        || ("mailto".equals(sender.getScheme()) && sender.getSchemeSpecificPart().matches("[^\\s?@]+@[^\\s?@]+\\.[^\\s?@]+"));
                if (!validSender || subject.length() > 255 || subject.chars().anyMatch(Character::isISOControl)
                        || !PushRegistrationConfiguration.isValidApplicationServerKey(publicKey)
                        || Base64.getUrlDecoder().decode(privateKey.strip()).length != 32)
                    throw new IllegalArgumentException();
                // Library validates the curve point and that both keys form the same pair.
                configured = new WebPush(subject.strip(), VapidKeys.fromUncompressedBytes(publicKey.strip(), privateKey.strip()));
            } catch (Exception exception) {
                // Never attach a parsing exception that could echo deployment secrets.
                throw new IllegalStateException("Enabled Web Push delivery requires a valid subject and matching P-256 public/private keys");
            }
        }
        webPush = configured;
    }

    public boolean enabled() { return enabled; }
    Duration requestTimeout() { return requestTimeout; }
    WebPush webPush() { return webPush; }

    URI endpoint(String value) {
        if (value == null || value.length() > 4096 || !value.equals(value.strip())) throw new IllegalArgumentException();
        URI uri = URI.create(value);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !hosts.contains(host.toLowerCase(Locale.ROOT))
                || uri.getRawUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawPath().isEmpty())
            throw new IllegalArgumentException();
        return uri;
    }

    private static boolean validProviderHost(String host) {
        if (!host.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+")
                || host.length() > 253 || host.matches("[0-9.]+") || host.matches(".*\\.[0-9]+")
                || Arrays.stream(host.split("\\.")).anyMatch(label -> label.length() > 63)) return false;
        return Set.of(".localhost", ".local", ".internal", ".test", ".invalid", ".example", ".home", ".lan")
                .stream().noneMatch(host::endsWith);
    }
}
