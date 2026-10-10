package com.demo.upimesh;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.junit.jupiter.api.Assertions.*;

/** Hosted with no ADMIN_TOKEN configured: the protected actions are switched off for everyone, never left open. */
@ActiveProfiles("hosted")
class HostedWithoutTokenTest extends HttpTestSupport {

    @Autowired private Environment env;

    @Test
    void protectedActionsFailClosedEvenWithABearerToken() throws Exception {
        for (var req : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                MockMvcRequestBuilders.post("/api/mesh/reset"), MockMvcRequestBuilders.put("/api/mesh/settings"),
                MockMvcRequestBuilders.post("/api/experiments/run")}) {
            Resp r = call(req.header("Authorization", "Bearer anything"), "{\"type\":\"TTL\",\"seed\":1}");
            assertEquals(403, r.status());
            assertEquals("ADMIN_DISABLED", r.body().get("error").asText());
        }
        assertEquals(200, postJson("/api/mesh/gossip", "{\"rounds\":1}").status(), "ordinary actions still work");
    }

    @Test
    void theProfileShipsConservativeDefaults() {
        assertEquals("120", env.getProperty("upi.mesh.security.writes-per-minute"));
        assertEquals("900", env.getProperty("upi.mesh.security.reads-per-minute"));
        assertEquals("65536", env.getProperty("upi.mesh.security.max-body-bytes"));
        assertEquals("false", env.getProperty("spring.h2.console.enabled"));
    }

    @Test
    void healthStillSaysAdminActionsAreProtected() throws Exception {
        var h = getJson("/api/health").body();
        assertEquals("hosted", h.get("mode").asText());
        assertTrue(h.get("adminRequired").asBoolean());
    }
}
