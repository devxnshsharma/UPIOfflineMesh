package com.demo.upimesh;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The real "hosted" profile (application-hosted.properties) with an admin token supplied the way a platform would. */
@ActiveProfiles("hosted")
@TestPropertySource(properties = {"ADMIN_TOKEN=test-secret", "WRITES_PER_MINUTE=100000", "READS_PER_MINUTE=100000"}) // limits are unit tested; a long class of requests must not trip them
class HostedPolicyTest extends HttpTestSupport {

    @Autowired private Environment env;

    private Resp admin(MockHttpServletRequestBuilder req, String token, Object body) throws Exception {
        if (token != null) req.header("Authorization", "Bearer " + token);
        return call(req, body);
    }

    @Test
    void healthDescribesTheDeploymentWithoutSecrets() throws Exception {
        JsonNode h = getJson("/api/health").body();
        assertEquals("hosted", h.get("mode").asText());
        assertTrue(h.get("adminRequired").asBoolean());
        assertFalse(h.get("h2Console").asBoolean());
        assertFalse(getJson("/api/health").raw().getContentAsString().contains("test-secret"));
    }

    @Test
    void theHostedProfileTurnsTheDatabaseConsoleOffAndHidesErrorDetails() {
        assertEquals("false", env.getProperty("spring.h2.console.enabled"));
        assertEquals("never", env.getProperty("server.error.include-stacktrace"));
        assertEquals("never", env.getProperty("server.error.include-message"));
        assertEquals("native", env.getProperty("server.forward-headers-strategy"));
    }

    @Test
    void resetSettingsAndExperimentsNeedTheAdminToken() throws Exception {
        String[][] protectedCalls = {
                {"POST", "/api/mesh/reset", "{}"},
                {"PUT", "/api/mesh/settings", "{\"seed\":5}"},
                {"POST", "/api/experiments/run", "{\"type\":\"TTL\",\"runs\":1,\"values\":[3]}"}};
        for (String[] c : protectedCalls) {
            var req = (Supplier<MockHttpServletRequestBuilder>) () -> MockMvcRequestBuilders.request(HttpMethod.valueOf(c[0]), c[1]);

            Resp none = admin(req.get(), null, c[2]);
            assertEquals(401, none.status(), c[1]);
            assertEquals("ADMIN_REQUIRED", none.body().get("error").asText(), c[1]);
            assertEquals("Bearer", none.raw().getHeader("WWW-Authenticate"), c[1]);

            Resp wrong = admin(req.get(), "wrong-token", c[2]);
            assertEquals(401, wrong.status(), c[1]);
            assertEquals("ADMIN_REQUIRED", wrong.body().get("error").asText(), c[1]);

            assertEquals(200, admin(req.get(), "test-secret", c[2]).status(), c[1] + " with the right token");
        }
    }

    @Test
    void aRefusedResetChangesNothing() throws Exception {
        send("alice@demo", "bob@demo", "10", null, null);
        assertEquals(401, admin(MockMvcRequestBuilders.post("/api/mesh/reset"), null, "{}").status());
        assertEquals(1, getJson("/api/mesh/state").body().get("packets").size(), "the packet is still there");
        assertEquals(0, new BigDecimal("5000.00").compareTo(balance("alice@demo")));
    }

    @Test
    void theTokenIsMatchedExactlyAndOnlyAsABearerCredential() throws Exception {
        assertEquals(401, call(MockMvcRequestBuilders.post("/api/mesh/reset").header("Authorization", "test-secret"), "{}").status());
        assertEquals(401, call(MockMvcRequestBuilders.post("/api/mesh/reset").header("Authorization", "Basic dGVzdC1zZWNyZXQ="), "{}").status());
        assertEquals(401, admin(MockMvcRequestBuilders.post("/api/mesh/reset"), "test-secre", "{}").status());
        assertEquals(401, admin(MockMvcRequestBuilders.post("/api/mesh/reset"), "test-secret-x", "{}").status());
        assertEquals(401, admin(MockMvcRequestBuilders.post("/api/mesh/reset"), "", "{}").status());
        assertEquals(200, call(MockMvcRequestBuilders.post("/api/mesh/reset").header("Authorization", "bearer   test-secret  "), "{}").status(), "scheme is case-insensitive, whitespace is trimmed");
    }

    @Test
    void anEncodedPathDoesNotSlipPastTheCheck() throws Exception {
        send("alice@demo", "bob@demo", "10", null, null);
        // the guard sits on the handler, so any spelling Spring routes to "reset" is protected
        Resp r = call(MockMvcRequestBuilders.post(java.net.URI.create("/api/mesh/%72eset")), "{}");
        assertNotEquals(200, r.status(), "got " + r.status());
        assertEquals(1, getJson("/api/mesh/state").body().get("packets").size(), "nothing was reset");
    }

    @Test
    void everyOtherActionStaysOpenToVisitors() throws Exception {
        JsonNode sent = send("alice@demo", "bob@demo", "10", null, null);
        gossip(2);
        online("bridge-1", true);
        flush();
        assertEquals(200, postJson("/api/demo/replay", "{\"packetId\":\"" + sent.get("packetId").asText() + "\",\"kind\":\"IDENTICAL\",\"times\":2}").status());
        assertEquals(200, getJson("/api/experiments").status(), "stored experiment results can be read");
        assertEquals(200, getJson("/api/server-key").status());
    }

    @Test
    void hostedBudgetsAreSmaller() throws Exception {
        JsonNode t = getJson("/api/experiments/types").body().get(0);
        assertEquals(200, t.get("maxSimulations").asInt());
        assertEquals(2000, t.get("maxSubmissions").asInt());
        Resp tooBig = admin(MockMvcRequestBuilders.post("/api/experiments/run"), "test-secret", "{\"type\":\"TTL\",\"runs\":100,\"values\":[1,2,3]}");
        assertEquals(400, tooBig.status());
        assertTrue(tooBig.body().get("message").asText().contains("limit is 200"));
    }

    @Test
    void theLiveMeshIsCappedAtTheHostedPacketLimit() throws Exception {
        for (int i = 0; i < 200; i++) {
            Resp r = postJson("/api/demo/send", "{\"senderVpa\":\"alice@demo\",\"receiverVpa\":\"bob@demo\",\"amount\":1}");
            assertEquals(200, r.status(), "payment " + i);
        }
        Resp full = postJson("/api/demo/send", "{\"senderVpa\":\"alice@demo\",\"receiverVpa\":\"bob@demo\",\"amount\":1}");
        assertEquals(409, full.status());
        assertTrue(full.body().get("message").asText().contains("200 packets"));
    }

    @Test
    void everyResponseCarriesTheSecurityHeaders() throws Exception {
        var page = mvc.perform(MockMvcRequestBuilders.get("/index.html")).andReturn().getResponse();
        assertTrue(page.getHeader("Content-Security-Policy").contains("default-src 'self'"));
        assertEquals("nosniff", page.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", page.getHeader("X-Frame-Options"));
        assertEquals("no-store", getJson("/api/health").raw().getHeader("Cache-Control"));
    }

    @Test
    void otherWebsitesCannotWriteAndNoCorsPermissionIsEverGranted() throws Exception {
        var evilPost = mvc.perform(MockMvcRequestBuilders.post("/api/mesh/flush").header("Origin", "https://evil.example")).andReturn().getResponse();
        assertEquals(403, evilPost.getStatus());
        assertNull(evilPost.getHeader("Access-Control-Allow-Origin"));

        var preflight = mvc.perform(MockMvcRequestBuilders.options("/api/mesh/reset").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertNull(preflight.getHeader("Access-Control-Allow-Origin"));
        assertNotEquals(200, preflight.getStatus());

        var read = mvc.perform(MockMvcRequestBuilders.get("/api/stats").header("Origin", "https://evil.example")).andReturn().getResponse();
        assertNull(read.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void errorsNeverLeakInternals() throws Exception {
        Resp bad = postJson("/api/demo/send", "{not json");
        assertEquals(400, bad.status());
        String text = bad.raw().getContentAsString();
        for (String leak : new String[]{"Exception", "at com.", "at org.", "stackTrace", "trace", "jackson"}) {
            assertFalse(text.contains(leak), "error body leaks \"" + leak + "\": " + text);
        }
        assertEquals(5, bad.body().size(), "timestamp, status, error, message, details");
        String unknown = getJson("/api/does-not-exist").raw().getContentAsString();
        assertFalse(unknown.contains("trace") || unknown.contains("Exception"), unknown);
    }
}
