package com.educore.api.identity;

import com.educore.api.config.ApplicationProperties;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class SessionCookieFactory {

    private final ApplicationProperties properties;

    public SessionCookieFactory(ApplicationProperties properties) {
        this.properties = properties;
    }

    ResponseCookie create(String rawToken, boolean remember) {
        Duration maxAge = remember
                ? properties.security().rememberedSessionTtl()
                : Duration.ofSeconds(-1);
        return base(rawToken)
                .maxAge(maxAge)
                .build();
    }

    ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie
                .from(properties.security().sessionCookieName(), value)
                .httpOnly(true)
                .secure(properties.security().cookieSecure())
                .sameSite("Lax")
                .path("/");
        String domain = properties.security().cookieDomain();
        if (domain != null && !domain.isBlank()) builder.domain(domain);
        return builder;
    }
}
