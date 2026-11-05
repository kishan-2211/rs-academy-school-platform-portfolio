package com.educore.api.common.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Base64;

/** Exact provider-only HTTP boundary; never a wildcard session/MFA exclusion. */
public final class GatewayWebhookRequestPath {
    public static final String PREFIX = "/api/v1/public/fee-gateway/webhooks/";

    private GatewayWebhookRequestPath() {}

    public static boolean matches(HttpServletRequest request) {
        if (request == null || !"POST".equals(request.getMethod())) return false;
        String uri = request.getRequestURI(), context = request.getContextPath();
        if (uri == null || context == null || !uri.startsWith(context + PREFIX)) return false;
        return canonicalRouteKey(uri.substring((context + PREFIX).length()));
    }

    /** Syntax is not authority: the server registry must still resolve the exact configured route. */
    public static boolean canonicalRouteKey(String value) {
        if (value == null) return false;
        if (value.matches("[a-f0-9]{64}")) return true;
        if (!value.matches("[A-Za-z0-9_-]{43}")) return false;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
