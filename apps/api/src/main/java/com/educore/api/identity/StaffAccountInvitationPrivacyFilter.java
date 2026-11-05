package com.educore.api.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Covers these new secret-bearing routes even when security or JSON validation refuses the request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class StaffAccountInvitationPrivacyFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.matches("/api/v1/public/staff-account-invitations/(?:previews|acceptances)")
                || path.equals("/api/v1/staff-account-invitations/acceptances")
                || path.matches("/api/v1/schools/[0-9a-fA-F-]{36}/staff/[0-9a-fA-F-]{36}/account-invitations(?:/[0-9a-fA-F-]{36}/(?:revocations|handoffs))?"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        response.addHeader(HttpHeaders.VARY, "Cookie");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}
