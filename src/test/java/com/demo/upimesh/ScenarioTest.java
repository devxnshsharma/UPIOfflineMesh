package com.demo.upimesh;

import com.demo.upimesh.model.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** The eight mandatory demonstrations, driven end to end through the HTTP API and verified against real DB state. */
class ScenarioTest extends HttpTestSupport {

    private void assertUntouched() {
        assertEquals(new BigDecimal("5000.00"), balance("alice@demo"));
        assertEquals(new BigDecimal("1000.00"), balance("bob@demo"));
        assertEquals(new BigDecimal("500.00"), balance("dave@demo"));
        assertEquals(0, ledger.count());
    }

    @Test
    void scenario1_successfulOfflineToOnlinePayment() throws Exception {
        JsonNode sent = send("alice@demo", "bob@demo", "500", null, null);
        String id = sent.get("packetId").asText();
        assertEquals("IN_MESH", status(id));
        assertUntouched(); // the sender never contacted the backend

        gossip(1);
        assertEquals(java.util.List.of("phone-alice", "relay-1"), holders(id));
        gossip(3); // 4 rounds: even the 4-hop bridge now holds a copy
        assertTrue(holders(id).containsAll(java.util.List.of("bridge-1", "bridge-2", "bridge-3")));
        assertEquals("AT_BRIDGE", status(id));

        assertEquals(0, flush().get("uploadsAttempted").asInt(), "all bridges offline: nothing may be uploaded");
        assertUntouched();

        online("bridge-1", true);
        JsonNode f = flush();
        assertEquals(1, f.get("uploadsAttempted").asInt(), "only the online bridge uploads");
        assertEquals("SETTLED", f.get("results").get(0).get("outcome").asText());

        assertEquals(new BigDecimal("4500.00"), balance("alice@demo"));
        assertEquals(new BigDecimal("1500.00"), balance("bob@demo"));
        assertEquals(1, ledger.count());
        assertEquals("SETTLED", status(id));
        assertEquals(1, getJson("/api/stats").body().get("settlements").asInt());

        // The other bridges come online later and upload their identical copies: no second effect.
        online("bridge-2", true);
        online("bridge-3", true);
        JsonNode late = flush();
        assertEquals(2, count(late, "DUPLICATE_DROPPED"));
        assertEquals(new BigDecimal("4500.00"), balance("alice@demo"));
        assertEquals(1, ledger.count());
    }

    @Test
    void scenario2_duplicateDeliveryFromThreeBridgesAtOnce() throws Exception {
        JsonNode sent = send("alice@demo", "bob@demo", "100", "DUPLICATE", null);
        assertEquals(3, sent.get("replicatedTo").size(), "the packet is pre-replicated onto all three bridges");
        allBridgesOnline();

        JsonNode f = flush();

        assertEquals(3, f.get("uploadsAttempted").asInt());
        assertEquals(1, count(f, "SETTLED"));
        assertEquals(2, count(f, "DUPLICATE_DROPPED"));
        assertEquals(new BigDecimal("4900.00"), balance("alice@demo"));
        assertEquals(new BigDecimal("1100.00"), balance("bob@demo"));
        assertEquals(1, ledger.count());
        assertEquals(1, events.countByOutcomeAndSimulationFalse(Outcome.SETTLED));
        assertEquals(2, events.countByOutcomeAndSimulationFalse(Outcome.DUPLICATE_DROPPED));
    }

    @Test
    void scenario2b_identicalReplayAndRepackagedReplayThroughTheAttackEndpoint() throws Exception {
        String id = send("alice@demo", "bob@demo", "100", null, null).get("packetId").asText();

        JsonNode storm = postJson("/api/demo/replay",
                json.createObjectNode().put("packetId", id).put("kind", "IDENTICAL").put("times", 5)).body();
        assertEquals(1, storm.get("summary").get("SETTLED").asInt());
        assertEquals(4, storm.get("summary").get("DUPLICATE_DROPPED").asInt());
        assertEquals(1, ledger.count());

        // Same logical payment, brand-new ciphertext, travels through the mesh like any other packet.
        JsonNode again = postJson("/api/demo/replay",
                json.createObjectNode().put("packetId", id).put("kind", "REPACKAGED")).body();
        String repackagedId = again.get("injected").get("packetId").asText();
        assertNotEquals(id, repackagedId);
        assertNotEquals(sent(id), sent(repackagedId));
        gossip(4);
        allBridgesOnline();
        JsonNode f = flush();

        assertTrue(f.get("results").toString().contains("NONCE_REUSED"), f.toString());
        assertEquals("REJECTED", status(repackagedId));
        assertEquals(new BigDecimal("4900.00"), balance("alice@demo"), "still debited exactly once");
        assertEquals(1, ledger.count());

        assertEquals(404, postJson("/api/demo/replay",
                json.createObjectNode().put("packetId", "nope").put("kind", "IDENTICAL")).status());
    }

    private String sent(String packetId) throws Exception {
        return getJson("/api/mesh/packets/" + packetId).body().get("fingerprint").asText();
    }

    @Test
    void scenario3_tamperedCiphertextIsRejectedWithRealAuthenticatedDecryption() throws Exception {
        String id = send("alice@demo", "bob@demo", "100", "TAMPERED", null).get("packetId").asText();
        gossip(4);
        allBridgesOnline();

        JsonNode f = flush();

        assertEquals(1, count(f, "REJECTED"));
        assertEquals(2, count(f, "DUPLICATE_DROPPED"));
        assertTrue(f.get("results").toString().contains("DECRYPTION_FAILED"));
        assertEquals("REJECTED", status(id));
        assertUntouched();
        JsonNode rows = getJson("/api/transactions").body();
        boolean sawRejection = false;
        for (JsonNode r : rows) if ("DECRYPTION_FAILED".equals(r.path("reason").asText())) sawRejection = true;
        assertTrue(sawRejection, "the real rejection is visible in the ledger view");
    }

    @Test
    void scenario4_expiredPacketIsRejectedByFreshnessValidation() throws Exception {
        String id = send("alice@demo", "bob@demo", "100", "EXPIRED", null).get("packetId").asText();
        gossip(4);
        allBridgesOnline();

        JsonNode f = flush();

        assertTrue(f.get("results").toString().contains("STALE_TIMESTAMP"));
        assertEquals("REJECTED", status(id));
        assertUntouched();
    }

    @Test
    void scenario5_ttlExhaustionStopsForwardingBeforeAnyBridge() throws Exception {
        String id = send("alice@demo", "bob@demo", "100", null, 2).get("packetId").asText();

        gossip(10);

        assertEquals(java.util.List.of("phone-alice", "relay-1", "relay-2", "relay-3"), holders(id));
        assertEquals("TTL_EXHAUSTED", status(id));
        allBridgesOnline();
        assertEquals(0, flush().get("uploadsAttempted").asInt(), "no bridge ever received it");
        assertEquals(0, gossip(1).get("transfers").asInt());
        assertUntouched();
        JsonNode ev = getJson("/api/mesh/packets/" + id).body().get("events");
        boolean logged = false;
        for (JsonNode e : ev) if ("TTL_EXHAUSTED".equals(e.get("type").asText())) logged = true;
        assertTrue(logged);
    }

    @Test
    void scenario6_interruptedConnectivityThenSettlementWithoutResending() throws Exception {
        String id = send("alice@demo", "bob@demo", "250", null, null).get("packetId").asText();
        gossip(8); // relays store the packet while every bridge is offline
        assertEquals(0, flush().get("uploadsAttempted").asInt());
        assertEquals("AT_BRIDGE", status(id));
        assertUntouched();
        assertEquals(1, getJson("/api/stats").body().get("packetsAwaiting").asInt());

        online("bridge-2", true); // connectivity returns somewhere
        JsonNode f = flush();

        assertEquals("SETTLED", f.get("results").get(0).get("outcome").asText());
        assertEquals(new BigDecimal("4750.00"), balance("alice@demo"));
        assertEquals(1, getJson("/api/mesh/state").body().get("packets").size(), "the sender never had to repeat the payment");
        assertEquals(1, ledger.count());
    }

    @Test
    void scenario6b_aBridgeThatGoesOfflineAgainKeepsItsPacketForNextTime() throws Exception {
        send("alice@demo", "bob@demo", "100", null, null);
        gossip(4);
        online("bridge-1", true);
        online("bridge-1", false); // connectivity flaps before the upload happened
        assertEquals(0, flush().get("uploadsAttempted").asInt());
        online("bridge-1", true);
        assertEquals(1, count(flush(), "SETTLED"));
    }

    @Test
    void scenario7_insufficientFundsIsRejectedWithoutPartialUpdates() throws Exception {
        String id = send("dave@demo", "bob@demo", "600", null, null).get("packetId").asText();
        gossip(4);
        allBridgesOnline();

        JsonNode f = flush();

        assertEquals(1, count(f, "REJECTED"));
        assertTrue(f.get("results").toString().contains("INSUFFICIENT_FUNDS"));
        assertEquals("REJECTED", status(id));
        assertEquals(new BigDecimal("500.00"), balance("dave@demo"));
        assertEquals(new BigDecimal("1000.00"), balance("bob@demo"));
        assertEquals(0, ledger.count());
        JsonNode top = getJson("/api/transactions").body().get(0);
        assertEquals("REJECTED", top.get("outcome").asText());
        assertEquals("INSUFFICIENT_FUNDS", top.get("reason").asText());
        assertEquals("dave@demo", top.get("senderVpa").asText());
    }

    @Test
    void scenario8_concurrentPaymentsOnSharedAccountsStayConsistent() throws Exception {
        // 12 payments between 3 accounts, all replicated to all 3 bridges, all uploaded in one concurrent flush.
        String[][] flows = {{"alice@demo", "bob@demo", "10"}, {"bob@demo", "carol@demo", "20"},
                {"carol@demo", "alice@demo", "30"}, {"alice@demo", "carol@demo", "5"}};
        for (int round = 0; round < 3; round++) for (String[] f : flows) send(f[0], f[1], f[2], "DUPLICATE", null);
        allBridgesOnline();

        JsonNode f = flush();

        assertEquals(36, f.get("uploadsAttempted").asInt());
        assertEquals(12, count(f, "SETTLED"));
        assertEquals(24, count(f, "DUPLICATE_DROPPED"));
        assertEquals(12, ledger.count());
        // per round: alice -10 +30 -5 = +15 ; bob +10 -20 = -10 ; carol +20 -30 +5 = -5
        assertEquals(new BigDecimal("5045.00"), balance("alice@demo"));
        assertEquals(new BigDecimal("970.00"), balance("bob@demo"));
        assertEquals(new BigDecimal("2485.00"), balance("carol@demo"));
        BigDecimal total = accounts.findAll().stream().filter(a -> !a.getVpa().endsWith("@sim"))
                .map(a -> a.getBalance()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("9000.00").compareTo(total), "money is conserved");
    }
}
