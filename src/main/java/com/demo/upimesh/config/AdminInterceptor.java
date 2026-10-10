package com.demo.upimesh.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Enforces {@link AdminOnly} on hosted deployments: a bearer token checked by the backend, never by the browser. */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final MeshProperties.Security security;
    private final byte[] tokenHash;

    public AdminInterceptor(MeshProperties props) {
        this.security = props.security();
        this.tokenHash = sha256(security.adminToken().trim());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!security.hosted() || !(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(AdminOnly.class)) {
            return true;
        }
        if (security.adminToken().isBlank()) {
            throw new ApiAccessException(HttpStatus.FORBIDDEN, "ADMIN_DISABLED",
                    "This action is switched off on this deployment. Run the project locally to use it.");
        }
        String header = request.getHeader("Authorization");
        boolean ok = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && MessageDigest.isEqual(sha256(header.substring(7).trim()), tokenHash); // constant time, equal lengths
        if (!ok) {
            throw new ApiAccessException(HttpStatus.UNAUTHORIZED, "ADMIN_REQUIRED",
                    "This action needs the admin token (Authorization: Bearer <token>).");
        }
        return true;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
