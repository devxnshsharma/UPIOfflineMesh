package com.demo.upimesh.config;

import com.demo.upimesh.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Edge policy in front of everything: browser security headers, a cap on request bodies, a per-client rate limit
 * and (hosted deployments only) a refusal of state-changing requests that come from another website.
 * Limits are off locally (0 = unlimited) and switched on by the hosted profile.
 * ponytail: fixed one-minute windows in memory, one process. Behind several instances put the limit in the proxy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AccessPolicyFilter extends OncePerRequestFilter {

    /** No inline script or style, no third-party origin: the dashboard is one HTML file, one script, one stylesheet. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
            + "font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    private static final int MAX_CLIENTS = 10_000;

    private record Window(long minute, AtomicInteger count) {}

    private final MeshProperties.Security security;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AccessPolicyFilter(MeshProperties props, ObjectMapper json, Clock clock) {
        this.security = props.security();
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        if (!path.startsWith("/h2-console")) securityHeaders(req, res); // the developer console needs its own inline scripts
        if (!path.startsWith("/api/")) { chain.doFilter(req, res); return; }

        res.setHeader("Cache-Control", "no-store");
        String method = req.getMethod();
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));

        if (write && security.hosted() && foreignOrigin(req)) {
            reject(res, 403, "FORBIDDEN_ORIGIN", "Requests from other websites are not accepted.");
            return;
        }
        if (req.getContentLengthLong() > security.maxBodyBytes()) {
            reject(res, 413, "PAYLOAD_TOO_LARGE", "The request body is larger than " + security.maxBodyBytes() + " bytes.");
            return;
        }
        int limit = write ? security.writesPerMinute() : security.readsPerMinute();
        if (!allow(req.getRemoteAddr() + (write ? "|w" : "|r"), limit)) {
            res.setHeader("Retry-After", String.valueOf(60 - clock.millis() / 1000 % 60));
            reject(res, 429, "RATE_LIMITED", "Too many requests from this address. Wait a moment and try again.");
            return;
        }
        // A body without a declared length (chunked) is still capped while it is read.
        chain.doFilter(write && req.getContentLengthLong() < 0 ? new LimitedRequest(req, security.maxBodyBytes()) : req, res);
    }

    private static void securityHeaders(HttpServletRequest req, HttpServletResponse res) {
        res.setHeader("Content-Security-Policy", CSP);
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        res.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (req.isSecure()) res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains"); // only meaningful over https
    }

    /** Browsers always send Origin on cross-site writes; a non-browser client sends none and is judged by the other rules. */
    private static boolean foreignOrigin(HttpServletRequest req) {
        String origin = req.getHeader("Origin");
        if (origin == null || origin.isBlank()) return false;
        try {
            String host = URI.create(origin).getHost();
            return host == null || !host.equalsIgnoreCase(req.getServerName());
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    private boolean allow(String client, int perMinute) {
        if (perMinute <= 0) return true;
        long minute = clock.millis() / 60_000;
        if (windows.size() > MAX_CLIENTS) windows.values().removeIf(w -> w.minute() < minute);
        Window w = windows.compute(client, (k, old) -> old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        return w.count().incrementAndGet() <= perMinute;
    }

    private void reject(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getWriter(), new ApiError(Instant.now(), status, code, message, List.of()));
    }

    /** Wraps a request so reading more than {@code max} bytes fails instead of filling memory. */
    static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long max;

        LimitedRequest(HttpServletRequest request, long max) {
            super(request);
            this.max = max;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long seen;

                @Override public int read() throws IOException { int b = in.read(); if (b >= 0) count(1); return b; }
                @Override public int read(byte[] buf, int off, int len) throws IOException { int n = in.read(buf, off, len); if (n > 0) count(n); return n; }
                @Override public boolean isFinished() { return in.isFinished(); }
                @Override public boolean isReady() { return in.isReady(); }
                @Override public void setReadListener(ReadListener listener) { in.setReadListener(listener); }

                private void count(int n) throws IOException {
                    seen += n;
                    if (seen > max) throw new IOException("request body larger than " + max + " bytes");
                }
            };
        }
    }
}
