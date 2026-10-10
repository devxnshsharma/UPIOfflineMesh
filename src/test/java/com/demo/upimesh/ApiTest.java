package com.demo.upimesh;

import com.demo.upimesh.dto.IngestResult;
import com.demo.upimesh.model.MeshPacket;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Contract of every endpoint, including the error cases. */
class ApiTest extends HttpTestSupport {

    // ----------------------------------------------------------------- read endpoints

    @Test
    void healthReportsBackendDatabaseAndMeshStatus() throws Exception {
        Resp r = getJson("/api/health");
        assertEquals(200, r.status());
        assertEquals("UP", r.body().get("status").asText());
        assertEquals("UP", r.body().get("database").asText());
        assertEquals(8, r.body().get("devices").asInt());
        assertTrue(r.body().get("uptimeSeconds").asLong() >= 0);
        assertEquals("local", r.body().get("mode").asText());
        assertFalse(r.body().get("adminRequired").asBoolean());
        assertTrue(r.body().get("h2Console").asBoolean(), "the developer console is on for local runs");
    }

    @Test
    void serverKeyExposesOnlyAValidRsa2048PublicKey() throws Exception {
        Resp r = getJson("/api/server-key");
        assertEquals(200, r.status());
        byte[] der = Base64.getDecoder().decode(r.body().get("publicKey").asText());
        var key = java.security.KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(der));
        assertEquals(2048, ((java.security.interfaces.RSAPublicKey) key).getModulus().bitLength());
        assertEquals(64, r.body().get("fingerprint").asText().length());
        assertFalse(r.raw().getContentAsString().toLowerCase().contains("private"));
    }

    @Test
    void accountsListsTheFourSimulatedAccountsAndHidesExperimentAccounts() throws Exception {
        JsonNode accts = getJson("/api/accounts").body();
        assertEquals(4, accts.size());
        assertEquals("alice@demo", accts.get(0).get("vpa").asText());
        assertEquals(0, new BigDecimal("5000.00").compareTo(accts.get(0).get("balance").decimalValue()));
        accts.forEach(a -> assertFalse(a.get("vpa").asText().endsWith("@sim")));
    }

    @Test
    void meshStateDescribesTopologyDevicesAndSettings() throws Exception {
        JsonNode s = getJson("/api/mesh/state").body();
        assertEquals("demo", s.get("topology").asText());
        assertEquals(8, s.get("devices").size());
        assertTrue(s.get("links").size() >= 7);
        assertEquals(5, s.get("settings").get("defaultTtl").asInt());
        assertEquals(0, s.get("packets").size());
        assertEquals(3, s.get("counters").get("bridgesTotal").asInt());
        assertEquals(0, s.get("counters").get("bridgesOnline").asInt());
    }

    @Test
    void statsAreDerivedFromRealState() throws Exception {
        JsonNode s = getJson("/api/stats").body();
        assertEquals(8, s.get("devicesTotal").asInt());
        assertEquals(0, s.get("settlements").asInt());
        assertEquals(0, s.get("packetsAwaiting").asInt());

        send("alice@demo", "bob@demo", "10", null, null);
        assertEquals(1, getJson("/api/stats").body().get("packetsAwaiting").asInt());
    }

    // --------------------------------------------------------------------- /demo/send

    @Test
    void sendEncryptsAndInjectsAtTheSenderPhoneWithoutTouchingTheBackend() throws Exception {
        JsonNode r = send("alice@demo", "bob@demo", "500", null, null);

        assertEquals("phone-alice", r.get("injectedAt").asText());
        assertEquals(5, r.get("ttl").asInt());
        assertEquals("NORMAL", r.get("mode").asText());
        assertEquals(64, r.get("fingerprint").asText().length());
        String text = r.toString();
        assertFalse(text.contains("alice@demo") || text.contains("bob@demo"), "response must not leak the plaintext payment");
        assertEquals(java.util.List.of("phone-alice"), holders(r.get("packetId").asText()));
        assertEquals(0, ledger.count(), "sending never contacts the settlement endpoint");
        assertEquals(0, packets.count());
        assertEquals(new BigDecimal("5000.00"), balance("alice@demo"));
    }

    @Test
    void sendValidatesInput() throws Exception {
        Resp negative = postJson("/api/demo/send", "{\"senderVpa\":\"alice@demo\",\"receiverVpa\":\"bob@demo\",\"amount\":-5}");
        assertEquals(400, negative.status());
        assertEquals(400, negative.body().get("status").asInt());
        assertTrue(negative.body().get("details").toString().contains("amount"));

        assertEquals(400, postJson("/api/demo/send", "{\"receiverVpa\":\"bob@demo\",\"amount\":5}").status());
        assertEquals(400, postJson("/api/demo/send", "{\"senderVpa\":\"a\",\"receiverVpa\":\"b\",\"amount\":5.123}").status());
        assertEquals(400, postJson("/api/demo/send", "{\"senderVpa\":\"a\",\"receiverVpa\":\"b\",\"amount\":5,\"ttl\":0}").status());
        assertEquals(400, postJson("/api/demo/send", "{not json").status());
        assertEquals(400, postJson("/api/demo/send",
                "{\"senderVpa\":\"a\",\"receiverVpa\":\"b\",\"amount\":5,\"startDevice\":\"nope\"}").status());
        assertEquals(0, getJson("/api/mesh/state").body().get("packets").size());
    }

    @Test
    void sendFromAPhoneThatLeftTheMeshIsAConflictAndCreatesNothing() throws Exception {
        assertEquals(200, patchJson("/api/mesh/devices/relay-1", "{\"active\":false}").status());
        Resp r = postJson("/api/demo/send",
                "{\"senderVpa\":\"alice@demo\",\"receiverVpa\":\"bob@demo\",\"amount\":5,\"startDevice\":\"relay-1\"}");
        assertEquals(409, r.status());
        assertEquals("CONFLICT", r.body().get("error").asText());
        assertEquals(0, getJson("/api/mesh/state").body().get("packets").size());
    }

    // ------------------------------------------------------------------ mesh controls

    @Test
    void gossipAcceptsAnOptionalRoundCountAndRejectsNonsense() throws Exception {
        send("alice@demo", "bob@demo", "10", null, null);
        JsonNode one = postJson("/api/mesh/gossip", null).body();
        assertEquals(1, one.get("rounds").asInt());
        assertEquals(1, one.get("transfers").asInt());

        JsonNode three = gossip(3);
        assertEquals(3, three.get("perRound").size());
        assertEquals(400, postJson("/api/mesh/gossip", "{\"rounds\":0}").status());
        assertEquals(400, postJson("/api/mesh/gossip", "{\"rounds\":1000}").status());
    }

    @Test
    void flushWithNoOnlineBridgeUploadsNothing() throws Exception {
        send("alice@demo", "bob@demo", "10", null, null);
        gossip(5);
        JsonNode f = flush();
        assertEquals(0, f.get("uploadsAttempted").asInt());
        assertEquals(0, ledger.count());
    }

    @Test
    void deviceControlTogglesBridgeInternetAndRejectsRelaysAndUnknownDevices() throws Exception {
        assertEquals(200, patchJson("/api/mesh/devices/bridge-1", "{\"online\":true}").status());
        assertEquals(1, getJson("/api/mesh/state").body().get("counters").get("bridgesOnline").asInt());
        assertEquals(400, patchJson("/api/mesh/devices/relay-1", "{\"online\":true}").status());
        assertEquals(404, patchJson("/api/mesh/devices/ghost", "{\"online\":true}").status());
        assertEquals(200, patchJson("/api/mesh/devices/relay-1", "{\"active\":false}").status());
        assertEquals(7, getJson("/api/mesh/state").body().get("counters").get("devicesActive").asInt());
    }

    @Test
    void settingsCanBeReadUpdatedAndAreValidated() throws Exception {
        Resp r = putJson("/api/mesh/settings", "{\"defaultTtl\":3,\"lossProbability\":0.25,\"delayRounds\":1}");
        assertEquals(200, r.status());
        JsonNode s = getJson("/api/mesh/settings").body();
        assertEquals(3, s.get("defaultTtl").asInt());
        assertEquals(0.25, s.get("lossProbability").asDouble());
        assertEquals(1, s.get("delayRounds").asInt());
        assertEquals(400, putJson("/api/mesh/settings", "{\"lossProbability\":1.5}").status());
        assertEquals(3, send("alice@demo", "bob@demo", "5", null, null).get("ttl").asInt(), "new default TTL applies to new payments");
    }

    @Test
    void resetRestoresTheBaselineAndCanSwitchTopology() throws Exception {
        send("alice@demo", "bob@demo", "100", null, null);
        gossip(4);
        allBridgesOnline();
        flush();
        assertEquals(1, ledger.count());
        putJson("/api/mesh/settings", "{\"lossProbability\":0.5}");

        Resp r = postJson("/api/mesh/reset", null);
        assertEquals(200, r.status());

        assertEquals(new BigDecimal("5000.00"), balance("alice@demo"));
        assertEquals(new BigDecimal("1000.00"), balance("bob@demo"));
        assertEquals(0, ledger.count());
        assertEquals(0, packets.count());
        assertEquals(0, getJson("/api/transactions").body().size());
        JsonNode s = getJson("/api/mesh/state").body();
        assertEquals(0, s.get("packets").size());
        assertEquals(0, s.get("settings").get("lossProbability").asDouble());
        assertEquals(0, s.get("counters").get("bridgesOnline").asInt());

        assertEquals(200, postJson("/api/mesh/reset", "{\"topology\":\"line\"}").status());
        assertEquals("line", getJson("/api/mesh/state").body().get("topology").asText());
        assertEquals(6, getJson("/api/mesh/state").body().get("devices").size());
        assertEquals(400, postJson("/api/mesh/reset", "{\"topology\":\"moon\"}").status());
    }

    @Test
    void packetInspectionShowsFingerprintHoldersHistoryAndOutcome() throws Exception {
        String id = send("alice@demo", "bob@demo", "100", null, null).get("packetId").asText();
        gossip(4);
        allBridgesOnline();
        flush();

        JsonNode d = getJson("/api/mesh/packets/" + id).body();
        assertEquals("SETTLED", d.get("status").asText());
        assertEquals("SETTLED", d.get("finalOutcome").asText());
        assertEquals(64, d.get("fingerprint").asText().length());
        assertTrue(d.get("ciphertextPreview").asText().endsWith("..."));
        assertEquals(8, d.get("holders").size(), "after 4 rounds every device holds a copy");
        assertTrue(d.get("events").size() > 8);
        assertEquals(3, d.get("uploads").size());
        assertEquals(404, getJson("/api/mesh/packets/does-not-exist").status());
        assertEquals("NOT_FOUND", getJson("/api/mesh/packets/does-not-exist").body().get("error").asText());
    }

    @Test
    void packetInspectionOffersTheWholeOpaqueCiphertextAndNeverAPaymentField() throws Exception {
        String id = send("alice@demo", "bob@demo", "123.45", null, null).get("packetId").asText();
        Resp r = getJson("/api/mesh/packets/" + id);
        JsonNode d = r.body();
        String cipher = d.get("ciphertext").asText();
        assertEquals(d.get("ciphertextLength").asInt(), cipher.length());
        assertTrue(cipher.startsWith(d.get("ciphertextPreview").asText().replace("...", "")), "the preview is the start of the same bytes");
        assertEquals(d.get("fingerprint").asText(), com.demo.upimesh.crypto.Hashing.sha256Hex(cipher), "the fingerprint is the SHA-256 of exactly these bytes");
        assertDoesNotThrow(() -> Base64.getDecoder().decode(cipher), "it is plain Base64");
        String all = r.raw().getContentAsString();
        for (String secret : new String[]{"alice@demo", "bob@demo", "123.45", "senderVpa", "receiverVpa", "nonce"}) {
            assertFalse(all.contains(secret), "relay view must not contain \"" + secret + "\"");
        }
    }

    @Test
    void noCrossOriginPermissionIsEverGrantedAndErrorsLeakNothing() throws Exception {
        var read = mvc.perform(MockMvcRequestBuilders.get("/api/stats").header("Origin", "https://evil.example")).andReturn().getResponse();
        assertNull(read.getHeader("Access-Control-Allow-Origin"));
        var preflight = mvc.perform(MockMvcRequestBuilders.options("/api/mesh/gossip").header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertNull(preflight.getHeader("Access-Control-Allow-Origin"));
        String bad = postJson("/api/demo/send", "{broken").raw().getContentAsString();
        assertFalse(bad.contains("Exception") || bad.contains("at com.") || bad.contains("trace"), bad);
    }

    // --------------------------------------------------------------- /bridge/ingest

    private Resp ingest(MeshPacket p, String bridge, int hops) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/bridge/ingest").header("X-Bridge-Node-Id", bridge).header("X-Hop-Count", hops), p);
    }

    @Test
    void ingestSettlesThenDropsDuplicatesWithMeaningfulStatusCodes() throws Exception {
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "100");

        Resp first = ingest(p, "bridge-9", 2);
        assertEquals(200, first.status());
        assertEquals("SETTLED", first.body().get("outcome").asText());
        assertEquals(false, first.body().get("retryable").asBoolean());

        Resp dup = ingest(p, "bridge-8", 3);
        assertEquals(200, dup.status());
        assertEquals("DUPLICATE_DROPPED", dup.body().get("outcome").asText());

        assertEquals(new BigDecimal("4900.00"), balance("alice@demo"));
        assertEquals(1, ledger.count());
        assertEquals("bridge-9", ledger.findAll().get(0).getBridgeNodeId());
        assertEquals(2, ledger.findAll().get(0).getHopCount());
    }

    @Test
    void ingestRejectsTamperedAndStalePacketsWith422() throws Exception {
        MeshPacket good = pay.packet("alice@demo", "bob@demo", "100");
        byte[] raw = Base64.getDecoder().decode(good.ciphertext());
        raw[raw.length / 2] ^= 1;
        MeshPacket tampered = new MeshPacket(good.packetId(), 5, good.createdAt(), Base64.getEncoder().encodeToString(raw));

        Resp r = ingest(tampered, "b", 1);
        assertEquals(422, r.status());
        assertEquals("REJECTED", r.body().get("outcome").asText());
        assertEquals("DECRYPTION_FAILED", r.body().get("reason").asText());

        long old = System.currentTimeMillis() - 25L * 3600 * 1000;
        Resp stale = ingest(pay.wrap(pay.instruction("alice@demo", "bob@demo", "5", old)), "b", 1);
        assertEquals(422, stale.status());
        assertEquals("STALE_TIMESTAMP", stale.body().get("reason").asText());
        assertEquals(new BigDecimal("5000.00"), balance("alice@demo"));
        assertEquals(0, ledger.count());
    }

    @Test
    void ingestValidatesRequestStructure() throws Exception {
        assertEquals(400, postJson("/api/bridge/ingest", "{\"packetId\":\"x\",\"ttl\":1,\"createdAt\":1,\"ciphertext\":\"\"}").status());
        assertEquals(400, postJson("/api/bridge/ingest", "{\"ttl\":1,\"createdAt\":1,\"ciphertext\":\"abc\"}").status());
        assertEquals(400, postJson("/api/bridge/ingest", "{\"packetId\":\"x\",\"ttl\":-1,\"createdAt\":1,\"ciphertext\":\"abc\"}").status());
        assertEquals(400, postJson("/api/bridge/ingest", "garbage").status());
        assertEquals(0, packets.count(), "malformed requests claim nothing");
    }

    @Test
    void threeConcurrentHttpIngestRequestsForTheSamePacketSettleOnce() throws Exception {
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "100");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        var gate = new java.util.concurrent.CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Resp>>();
        for (int i = 0; i < 3; i++) {
            String node = "bridge-" + i;
            futures.add(pool.submit(() -> { gate.await(); return ingest(p, node, 3); }));
        }
        gate.countDown();
        int settled = 0, dups = 0;
        for (var f : futures) {
            String o = f.get().body().get("outcome").asText();
            if (o.equals("SETTLED")) settled++; else if (o.equals("DUPLICATE_DROPPED")) dups++;
        }
        pool.shutdown();
        assertEquals(1, settled);
        assertEquals(2, dups);
        assertEquals(new BigDecimal("4900.00"), balance("alice@demo"));
        assertEquals(1, ledger.count());
    }

    // ------------------------------------------------------------------ /transactions

    @Test
    void transactionsShowSettledDuplicateAndRejectedRowsDistinctly() throws Exception {
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "100");
        ingest(p, "b1", 1);
        ingest(p, "b2", 1);
        ingest(pay.packet("dave@demo", "bob@demo", "9999"), "b1", 1);

        JsonNode rows = getJson("/api/transactions").body();
        assertEquals(3, rows.size());
        assertEquals("REJECTED", rows.get(0).get("outcome").asText(), "newest first");
        assertEquals("INSUFFICIENT_FUNDS", rows.get(0).get("reason").asText());
        assertEquals("DUPLICATE_DROPPED", rows.get(1).get("outcome").asText());
        assertEquals("SETTLED", rows.get(2).get("outcome").asText());
        assertEquals("alice@demo", rows.get(2).get("senderVpa").asText());
        assertEquals("alice@demo", rows.get(1).get("senderVpa").asText(), "duplicate of a settled payment still shows the parties");
        assertFalse(rows.get(2).has("ciphertext"));
    }

    // ---------------------------------------------------------------------- errors

    @Test
    void unknownRoutesAndWrongMethodsGetStructuredErrors() throws Exception {
        Resp missing = getJson("/api/nope");
        assertEquals(404, missing.status());
        Resp wrongMethod = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/accounts"), null);
        assertEquals(405, wrongMethod.status());
    }
}
