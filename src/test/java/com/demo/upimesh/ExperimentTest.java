package com.demo.upimesh;

import com.demo.upimesh.dto.ExperimentRequest;
import com.demo.upimesh.dto.ExperimentResult;
import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.service.ExperimentService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static com.demo.upimesh.dto.ExperimentRequest.Type.*;
import static org.junit.jupiter.api.Assertions.*;

/** Networking experiments run the real mesh + the real backend pipeline and must be reproducible. */
class ExperimentTest extends HttpTestSupport {

    @Autowired private ExperimentService experiments;

    private ExperimentResult run(ExperimentRequest.Type type, int runs, Double... values) {
        return experiments.run(new ExperimentRequest(type, runs, 7L, List.of(values)));
    }

    private double metric(ExperimentResult r, int row, String key) {
        return r.rows().get(row).metrics().get(key);
    }

    @Test
    void duplicateDeliveryAlwaysYieldsExactlyOneSettlement() {
        ExperimentResult r = run(DUPLICATE_DELIVERY, 3, 1.0, 3.0, 8.0);
        for (int i = 0; i < r.rows().size(); i++) {
            double k = r.rows().get(i).param();
            assertEquals(1.0, metric(r, i, "avgSettlements"), "k=" + k);
            assertEquals(k - 1, metric(r, i, "avgDuplicateDrops"), "k=" + k);
            assertEquals(100.0, metric(r, i, "exactlyOncePct"), "k=" + k);
        }
    }

    @Test
    void ttlDecidesWhetherAnyBridgeIsReachable() {
        ExperimentResult r = run(TTL, 2, 1.0, 2.0, 3.0, 5.0);
        assertEquals(0.0, metric(r, 0, "deliveryRatePct"));
        assertEquals(0.0, metric(r, 1, "deliveryRatePct"));
        assertEquals(100.0, metric(r, 2, "deliveryRatePct"));
        assertEquals(100.0, metric(r, 3, "deliveryRatePct"));
        assertTrue(metric(r, 0, "avgReachabilityPct") < metric(r, 1, "avgReachabilityPct"));
        assertTrue(metric(r, 1, "avgReachabilityPct") < metric(r, 2, "avgReachabilityPct"));
    }

    @Test
    void packetLossLowersDeliveryRate() {
        ExperimentResult r = run(PACKET_LOSS, 20, 0.0, 0.95);
        assertEquals(100.0, metric(r, 0, "deliveryRatePct"));
        assertTrue(metric(r, 1, "deliveryRatePct") < 100.0);
        assertEquals(0.0, metric(r, 0, "avgLost"));
        assertTrue(metric(r, 1, "avgLost") > 0);
    }

    @Test
    void laterBridgeConnectivityMeansLaterSettlement() {
        ExperimentResult r = run(CONNECTIVITY_DELAY, 3, 0.0, 10.0);
        assertEquals(100.0, metric(r, 1, "deliveryRatePct"));
        assertTrue(metric(r, 1, "avgSettlementDelayRounds") >= 10.0);
        assertTrue(metric(r, 1, "avgSettlementDelayRounds") > metric(r, 0, "avgSettlementDelayRounds"));
    }

    @Test
    void moreOnlineBridgesImproveDelivery() {
        ExperimentResult r = run(BRIDGE_AVAILABILITY, 25, 0.0, 3.0);
        assertEquals(0.0, metric(r, 0, "deliveryRatePct"), "no bridge online: nothing can settle");
        assertTrue(metric(r, 1, "deliveryRatePct") > 50.0);
    }

    @Test
    void moreGossipRoundsReachMoreDevicesAndCreateMoreDuplicateUploads() {
        ExperimentResult r = run(GOSSIP_ROUNDS, 10, 1.0, 8.0);
        assertEquals(0.0, metric(r, 0, "deliveryRatePct"));
        assertTrue(metric(r, 1, "deliveryRatePct") > 0.0);
        assertTrue(metric(r, 1, "avgCopies") > metric(r, 0, "avgCopies"));
        assertTrue(metric(r, 1, "avgDuplicateUploads") > 0, "several bridges end up uploading the same packet");
    }

    @Test
    void sameParametersAndSeedReproduceIdenticalResults() {
        ExperimentResult a = run(PACKET_LOSS, 15, 0.2, 0.5);
        ExperimentResult b = run(PACKET_LOSS, 15, 0.2, 0.5);
        assertEquals(a.rows(), b.rows());
        assertEquals(a.parameters(), b.parameters());
        assertEquals(7L, a.parameters().get("seed"));
        assertEquals(15, a.parameters().get("runsPerPoint"));
        assertEquals(List.of(0.2, 0.5), a.parameters().get("values"));
    }

    @Test
    void experimentsLeaveTheDemoAccountsLedgerAndMeshUntouched() throws Exception {
        run(DUPLICATE_DELIVERY, 2, 3.0);
        run(TTL, 1, 4.0);

        assertEquals(new BigDecimal("5000.00"), balance("alice@demo"));
        assertEquals(4, getJson("/api/accounts").body().size());
        assertEquals(0, getJson("/api/transactions").body().size());
        assertEquals(0, events.countByOutcomeAndSimulationFalse(Outcome.SETTLED));
        assertTrue(events.countByOutcomeAndSimulationTrue(Outcome.SETTLED) > 0, "experiments really used the backend");
        JsonNode stats = getJson("/api/stats").body();
        assertEquals(0, stats.get("settlements").asInt());
        assertEquals(0, getJson("/api/mesh/state").body().get("packets").size());
    }

    @Test
    void invalidExperimentParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> run(PACKET_LOSS, 5, 1.5));
        assertThrows(IllegalArgumentException.class, () -> run(TTL, 5, 0.0));
        assertThrows(IllegalArgumentException.class, () -> run(TTL, 5, 2.5));
        assertThrows(IllegalArgumentException.class, () -> run(BRIDGE_AVAILABILITY, 5, 9.0));
        assertThrows(IllegalArgumentException.class, () -> experiments.run(
                new ExperimentRequest(TTL, 5, 1L, List.of(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0))));
    }

    @Test
    void experimentsAreAvailableOverHttpWithDefaultsAndHistory() throws Exception {
        JsonNode types = getJson("/api/experiments/types").body();
        assertEquals(6, types.size());
        assertTrue(types.get(0).has("defaultValues"));

        int before = getJson("/api/experiments").body().size();
        Resp r = postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":2}");
        assertEquals(200, r.status(), r.raw().getContentAsString());
        assertEquals("TTL", r.body().get("type").asText());
        assertTrue(r.body().get("rows").size() >= 5, "default value sweep");
        assertEquals(42, r.body().get("parameters").get("seed").asInt());

        JsonNode history = getJson("/api/experiments").body();
        assertEquals(Math.min(20, before + 1), history.size());
        assertEquals(r.body().get("id").asText(), history.get(0).get("id").asText(), "most recent first");
        assertEquals(400, postJson("/api/experiments/run", "{\"type\":\"NOPE\"}").status());
        assertEquals(400, postJson("/api/experiments/run", "{\"type\":\"TTL\",\"runs\":0}").status());
    }
}
