package com.demo.upimesh;

import com.demo.upimesh.dto.ExperimentRequest;
import com.demo.upimesh.service.ExperimentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.demo.upimesh.dto.ExperimentRequest.Type.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded processing: an experiment's cost is capped, only one runs at a time, and the live mesh cannot grow without limit. */
@TestPropertySource(properties = {"upi.mesh.experiments.max-simulations=400", "upi.mesh.experiments.max-submissions=60", "upi.mesh.max-packets=3"})
class ExperimentLimitsTest extends HttpTestSupport {

    @Autowired private ExperimentService experiments;

    @Test
    void typesDescribeTheirLimitsAndAcceptedRanges() throws Exception {
        JsonNode types = getJson("/api/experiments/types").body();
        assertEquals(6, types.size());
        for (JsonNode t : types) {
            assertEquals(12, t.get("maxValues").asInt());
            assertEquals(100, t.get("maxRuns").asInt());
            assertEquals(400, t.get("maxSimulations").asInt());
            assertEquals(60, t.get("maxSubmissions").asInt());
            assertTrue(t.get("valueMin").asDouble() <= t.get("valueMax").asDouble());
            // every default value is inside the range the form is told to enforce
            t.get("defaultValues").forEach(v -> {
                assertTrue(v.asDouble() >= t.get("valueMin").asDouble() && v.asDouble() <= t.get("valueMax").asDouble(), t.get("type").asText());
                if (t.get("wholeValues").asBoolean()) assertEquals(Math.rint(v.asDouble()), v.asDouble(), t.get("type").asText());
            });
        }
        JsonNode ttl = types.get(2);
        assertEquals("TTL", ttl.get("type").asText());
        assertEquals(1, ttl.get("valueMin").asInt());
        assertEquals(32, ttl.get("valueMax").asInt());
        assertTrue(ttl.get("wholeValues").asBoolean());
        JsonNode loss = types.get(1);
        assertFalse(loss.get("wholeValues").asBoolean());
        assertEquals(1.0, loss.get("valueMax").asDouble());
    }

    @Test
    void rangesReportedToTheFormAreExactlyWhatTheServerEnforces() throws Exception {
        assertEquals(200, postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":1,\"values\":[32]}").status());
        assertEquals(400, postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":1,\"values\":[33]}").status());
        assertEquals(400, postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":1,\"values\":[2.5]}").status());
        Resp r = postJson("/api/experiments/run", "{\"type\":\"PACKET_LOSS\",\"runs\":1,\"values\":[1.01]}");
        assertEquals(400, r.status());
        assertTrue(r.body().get("message").asText().contains("from 0 to 1"), r.body().get("message").asText());
    }

    @Test
    void theSimulationBudgetIsEnforcedAtTheBoundary() throws Exception {
        assertEquals(200, postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":2,\"values\":[1,2,3,4,5,6,7,8,9,10,11,12]}").status(), "12 x 2 = 24 is within 400");
        Resp over = postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":34,\"values\":[1,2,3,4,5,6,7,8,9,10,11,12]}");
        assertEquals(400, over.status(), "12 x 34 = 408 is over 400");
        String message = over.body().get("message").asText();
        assertTrue(message.contains("408 simulations") && message.contains("limit is 400") && message.contains("12 values x 34 runs"), message);
    }

    @Test
    void theDuplicateDeliveryBudgetCountsEverySubmittedPacket() throws Exception {
        assertEquals(200, postJson("/api/experiments/run", "{\"type\":\"DUPLICATE_DELIVERY\",\"runs\":4,\"values\":[5,10]}").status(), "4 x 15 = 60 submissions");
        Resp over = postJson("/api/experiments/run", "{\"type\":\"DUPLICATE_DELIVERY\",\"runs\":5,\"values\":[5,10]}");
        assertEquals(400, over.status(), "5 x 15 = 75 submissions");
        assertTrue(over.body().get("message").asText().contains("75 packets") && over.body().get("message").asText().contains("limit is 60"), over.body().get("message").asText());
    }

    @Test
    void onlyOneExperimentRunsAtATimeAndTheOtherIsToldToWait() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = pool.submit(() -> experiments.run(new ExperimentRequest(TTL, 100, 1L, List.of(1.0, 2.0, 3.0, 4.0))));
            Thread.sleep(200);
            Resp refused = null;
            long end = System.currentTimeMillis() + 6000;
            while (refused == null && System.currentTimeMillis() < end) {
                Resp r = postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":1,\"values\":[3]}");
                if (r.status() == 409) refused = r;
                else Thread.sleep(25);
            }
            assertNotNull(refused, "a second request while one is running must be refused");
            assertEquals("CONFLICT", refused.body().get("error").asText());
            assertTrue(refused.body().get("message").asText().contains("still running"));
            assertEquals(4, ((com.demo.upimesh.dto.ExperimentResult) first.get()).rows().size(), "the first experiment finishes normally");
            assertEquals(200, postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":1,\"values\":[3]}").status(), "the guard is released afterwards");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theLiveMeshRefusesNewPaymentsBeyondItsPacketLimitUntilReset() throws Exception {
        for (int i = 0; i < 3; i++) send("alice@demo", "bob@demo", "1", null, null);
        Resp full = postJson("/api/demo/send", "{\"senderVpa\":\"alice@demo\",\"receiverVpa\":\"bob@demo\",\"amount\":1}");
        assertEquals(409, full.status());
        assertTrue(full.body().get("message").asText().contains("already holds 3 packets"), full.body().get("message").asText());
        assertEquals(3, getJson("/api/mesh/state").body().get("packets").size(), "nothing was half-created");

        assertEquals(200, postJson("/api/mesh/reset", "{}").status());
        send("alice@demo", "bob@demo", "1", null, null);
    }

    @Test
    void experimentsDoNotCountAgainstTheLiveMeshLimit() throws Exception {
        for (int i = 0; i < 3; i++) send("alice@demo", "bob@demo", "1", null, null);
        assertEquals(200, postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":2,\"values\":[3,4]}").status());
    }
}
