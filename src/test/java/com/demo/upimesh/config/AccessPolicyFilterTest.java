package com.demo.upimesh.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The edge policy on its own: headers, body cap, rate limit and cross-site refusal, with a controllable clock. */
class AccessPolicyFilterTest {

    /** A clock the test can move, so rate-limit windows are exact instead of timing dependent. */
    static final class TestClock extends Clock {
        long millis = Instant.parse("2026-01-01T00:00:30Z").toEpochMilli();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TestClock clock = new TestClock();

    private AccessPolicyFilter filter(boolean hosted, int writes, int reads, long maxBody) {
        MeshProperties props = new MeshProperties(86400, 300, 5, BigDecimal.TEN, 86400, 3, 120, 500, List.of(),
                new MeshProperties.Sim(0, 0, 86400, 42), new MeshProperties.Experiments(600, 6000),
                new MeshProperties.Security(hosted, "", writes, reads, maxBody));
        return new AccessPolicyFilter(props, json, clock);
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRemoteAddr("10.0.0.1");
        return r;
    }

    private MockHttpServletResponse run(AccessPolicyFilter f, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void pagesCarryTheBrowserSecurityHeadersButTheDeveloperConsoleIsLeftAlone() throws Exception {
        AccessPolicyFilter f = filter(false, 0, 0, 65536);
        MockHttpServletResponse page = run(f, request("GET", "/index.html"));
        String csp = page.getHeader("Content-Security-Policy");
        assertTrue(csp.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'") && csp.contains("object-src 'none'"), csp);
        assertFalse(csp.contains("unsafe-inline") || csp.contains("unsafe-eval"), "no inline script or style is needed, so none is allowed");
        assertEquals("nosniff", page.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", page.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", page.getHeader("Referrer-Policy"));
        assertNull(page.getHeader("Strict-Transport-Security"), "HSTS must not be sent over plain http");

        MockHttpServletRequest secure = request("GET", "/index.html");
        secure.setSecure(true);
        assertTrue(run(f, secure).getHeader("Strict-Transport-Security").contains("max-age="));

        assertNull(run(f, request("GET", "/h2-console/login.do")).getHeader("Content-Security-Policy"));
    }

    @Test
    void apiResponsesAreNeverCached() throws Exception {
        assertEquals("no-store", run(filter(false, 0, 0, 65536), request("GET", "/api/health")).getHeader("Cache-Control"));
    }

    @Test
    void bodiesAboveTheLimitAreRefusedWith413AndTheUsualErrorShape() throws Exception {
        AccessPolicyFilter f = filter(false, 0, 0, 100);
        MockHttpServletRequest big = request("POST", "/api/demo/send");
        big.setContent(new byte[101]);
        MockHttpServletResponse res = run(f, big);
        assertEquals(413, res.getStatus());
        JsonNode body = json.readTree(res.getContentAsString());
        assertEquals("PAYLOAD_TOO_LARGE", body.get("error").asText());
        assertEquals(413, body.get("status").asInt());
        assertTrue(body.has("timestamp") && body.has("message") && body.has("details"));

        MockHttpServletRequest exact = request("POST", "/api/demo/send");
        exact.setContent(new byte[100]);
        assertEquals(200, run(f, exact).getStatus(), "a body exactly at the limit is accepted");
    }

    @Test
    void aBodyWithoutADeclaredLengthIsStillCappedWhileItIsRead() {
        MockHttpServletRequest req = request("POST", "/api/demo/send");
        req.setContent(new byte[50]);
        var limited = new AccessPolicyFilter.LimitedRequest(req, 10);
        assertThrows(IOException.class, () -> limited.getInputStream().readAllBytes());
        MockHttpServletRequest small = request("POST", "/api/demo/send");
        small.setContent(new byte[10]);
        assertDoesNotThrow(() -> new AccessPolicyFilter.LimitedRequest(small, 10).getInputStream().readAllBytes());
    }

    @Test
    void writesAreRateLimitedPerClientAndReadsHaveTheirOwnBudget() throws Exception {
        AccessPolicyFilter f = filter(false, 3, 5, 65536);
        for (int i = 0; i < 3; i++) assertEquals(200, run(f, request("POST", "/api/mesh/gossip")).getStatus(), "write " + i);
        MockHttpServletResponse limited = run(f, request("POST", "/api/mesh/gossip"));
        assertEquals(429, limited.getStatus());
        assertEquals("30", limited.getHeader("Retry-After"), "seconds left in the current minute");
        assertEquals("RATE_LIMITED", json.readTree(limited.getContentAsString()).get("error").asText());

        for (int i = 0; i < 5; i++) assertEquals(200, run(f, request("GET", "/api/stats")).getStatus(), "read " + i);
        assertEquals(429, run(f, request("GET", "/api/stats")).getStatus());

        MockHttpServletRequest other = request("POST", "/api/mesh/gossip");
        other.setRemoteAddr("10.0.0.2");
        assertEquals(200, run(f, other).getStatus(), "another client has its own budget");
        assertEquals(200, run(f, request("GET", "/index.html")).getStatus(), "static files are not limited");
    }

    @Test
    void theRateLimitWindowResetsEveryMinute() throws Exception {
        AccessPolicyFilter f = filter(false, 1, 0, 65536);
        assertEquals(200, run(f, request("POST", "/api/mesh/flush")).getStatus());
        assertEquals(429, run(f, request("POST", "/api/mesh/flush")).getStatus());
        clock.millis += 60_000;
        assertEquals(200, run(f, request("POST", "/api/mesh/flush")).getStatus());
    }

    @Test
    void zeroMeansUnlimited() throws Exception {
        AccessPolicyFilter f = filter(false, 0, 0, 65536);
        for (int i = 0; i < 200; i++) assertEquals(200, run(f, request("POST", "/api/mesh/flush")).getStatus());
    }

    @Test
    void crossSiteWritesAreRefusedOnHostedDeploymentsOnly() throws Exception {
        AccessPolicyFilter hosted = filter(true, 0, 0, 65536);
        MockHttpServletRequest evil = request("POST", "/api/mesh/flush");
        evil.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse res = run(hosted, evil);
        assertEquals(403, res.getStatus());
        assertEquals("FORBIDDEN_ORIGIN", json.readTree(res.getContentAsString()).get("error").asText());

        MockHttpServletRequest sandboxed = request("POST", "/api/mesh/flush");
        sandboxed.addHeader("Origin", "null");
        assertEquals(403, run(hosted, sandboxed).getStatus());

        MockHttpServletRequest own = request("POST", "/api/mesh/flush");
        own.addHeader("Origin", "http://localhost:8080");
        assertEquals(200, run(hosted, own).getStatus(), "the dashboard's own origin is accepted");
        assertEquals(200, run(hosted, request("POST", "/api/mesh/flush")).getStatus(), "no Origin header: not a browser cross-site request");

        MockHttpServletRequest read = request("GET", "/api/stats");
        read.addHeader("Origin", "https://evil.example");
        assertEquals(200, run(hosted, read).getStatus(), "reads are harmless: the browser hides the answer without CORS headers");

        MockHttpServletRequest local = request("POST", "/api/mesh/flush");
        local.addHeader("Origin", "https://evil.example");
        assertEquals(200, run(filter(false, 0, 0, 65536), local).getStatus(), "local mode keeps every client working");
    }
}
