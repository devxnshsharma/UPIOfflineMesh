package com.demo.upimesh;

import com.demo.upimesh.mesh.*;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.model.Reason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Pure simulation tests: topology, gossip, TTL, store-and-forward. No Spring, no database. */
class MeshNetworkTest {

    /** Clock the test can move forward. */
    static class TestClock extends Clock {
        long millis = 1_700_000_000_000L;
        void advanceSeconds(long s) { millis += s * 1000; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }

    private TestClock clock;

    @BeforeEach
    void setUp() {
        clock = new TestClock();
    }

    private static MeshSettings settings(double loss, int delay, long seed) {
        return new MeshSettings(5, loss, delay, 86_400, seed);
    }

    /** a - b - c - d(bridge) */
    private static Topology chain() {
        return Topology.builder().relay("a").relay("b").relay("c").bridge("d")
                .link("a", "b").link("b", "c").link("c", "d").build();
    }

    private MeshNetwork net(Topology t, MeshSettings s) {
        return new MeshNetwork(t, s, clock);
    }

    private MeshPacket packet(String id, int ttl) {
        return new MeshPacket(id, ttl, clock.millis(), "b3BhcXVlLWNpcGhlcnRleHQ=");
    }

    private void rounds(MeshNetwork n, int count) {
        for (int i = 0; i < count; i++) n.gossipRound();
    }

    // ------------------------------------------------------------ forwarding & TTL

    @Test
    void injectedPacketIsStoredAtTheOriginWithFullTtl() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        n.inject("a", packet("p1", 5), "test");

        assertEquals(Set.of("a"), n.holders("p1"));
        assertEquals(5, n.copy("a", "p1").orElseThrow().ttl());
        assertEquals(PacketStatus.IN_MESH, n.status("p1"));
    }

    @Test
    void oneGossipRoundMovesThePacketExactlyOneHopAndDecrementsTtl() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        n.inject("a", packet("p1", 5), "test");

        GossipResult r = n.gossipRound();

        assertEquals(1, r.transfers());
        assertEquals(Set.of("a", "b"), n.holders("p1"));
        assertEquals(4, n.copy("b", "p1").orElseThrow().ttl());
        assertEquals(1, n.copy("b", "p1").orElseThrow().hops());
        assertEquals("a", n.copy("b", "p1").orElseThrow().receivedFrom());

        n.gossipRound();
        assertEquals(Set.of("a", "b", "c"), n.holders("p1"));
        assertEquals(3, n.copy("c", "p1").orElseThrow().ttl());
    }

    @Test
    void relaysCarryTheCiphertextUnchanged() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        n.inject("a", packet("p1", 5), "test");
        rounds(n, 3);
        for (String id : List.of("a", "b", "c", "d")) {
            assertEquals("b3BhcXVlLWNpcGhlcnRleHQ=", n.copy(id, "p1").orElseThrow().ciphertext());
        }
    }

    @Test
    void ttlExhaustionStopsForwardingAndIsReportedClearly() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        n.inject("a", packet("p1", 2), "test"); // needs 3 hops to reach d

        rounds(n, 6);

        assertEquals(Set.of("a", "b", "c"), n.holders("p1"));
        assertEquals(0, n.copy("c", "p1").orElseThrow().ttl());
        assertFalse(n.holders("p1").contains("d"));
        assertEquals(PacketStatus.TTL_EXHAUSTED, n.status("p1"));
        assertEquals(0, n.gossipRound().transfers(), "nothing moves once every copy is out of hops");
        assertTrue(n.trace("p1").orElseThrow().events().stream().anyMatch(e -> e.type() == MeshEvent.Type.TTL_EXHAUSTED));
    }

    @Test
    void ttlDecidesWhichBridgesOfTheDemoTopologyAreReachable() {
        // phone-alice -> relay-1 -> relay-2 -> bridge-1 (3 hops); ... -> relay-4 -> bridge-2 (4 hops)
        for (int ttl = 1; ttl <= 5; ttl++) {
            MeshNetwork n = net(Topology.demo(), settings(0, 0, 1));
            n.inject("phone-alice", packet("p1", ttl), "test");
            rounds(n, 10);
            Set<String> holders = n.holders("p1");
            assertEquals(ttl >= 3, holders.contains("bridge-1"), "bridge-1 at ttl " + ttl);
            assertEquals(ttl >= 3, holders.contains("bridge-3"), "bridge-3 at ttl " + ttl);
            assertEquals(ttl >= 4, holders.contains("bridge-2"), "bridge-2 at ttl " + ttl);
        }
    }

    @Test
    void fullMeshReachesEveryoneInOneRound() {
        MeshNetwork n = net(Topology.fullMesh(), settings(0, 0, 1));
        n.inject("phone-alice", packet("p1", 5), "test");
        n.gossipRound();
        assertEquals(Topology.demo().devices().size(), n.holders("p1").size());
    }

    @Test
    void aDeviceStoresEachPacketOnceEvenWhenTwoNeighboursOfferIt() {
        // a - b - d and a - c - d : d is offered the packet by both b and c in the same round.
        Topology diamond = Topology.builder().relay("a").relay("b").relay("c").relay("d")
                .link("a", "b").link("a", "c").link("b", "d").link("c", "d").build();
        MeshNetwork n = net(diamond, settings(0, 0, 1));
        n.inject("a", packet("p1", 5), "test");

        GossipResult r1 = n.gossipRound();
        GossipResult r2 = n.gossipRound();

        assertEquals(2, r1.transfers());
        assertEquals(1, r2.transfers(), "only one of b/c actually transmits to d");
        assertTrue(r2.suppressed() >= 1, "the second offer is suppressed at the forwarding layer");
        assertEquals(1, n.devices().stream().filter(d -> d.id().equals("d")).findFirst().orElseThrow().copies().size());
        assertEquals(4, n.holders("p1").size());
    }

    // --------------------------------------------------------- store and forward

    @Test
    void packetIsRetainedWhileTheBridgeIsOfflineAndUploadableOnceItComesOnline() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        n.inject("a", packet("p1", 5), "test");
        rounds(n, 4);

        assertTrue(n.holders("p1").contains("d"));
        assertTrue(n.pendingUploads().isEmpty(), "bridge has no internet yet");
        assertEquals(PacketStatus.AT_BRIDGE, n.status("p1"));

        rounds(n, 3); // keep gossiping while offline: nothing is lost, the copies stay
        assertEquals(4, n.holders("p1").size());
        assertTrue(n.pendingUploads().isEmpty());

        n.setOnline("d", true);
        List<PendingUpload> uploads = n.pendingUploads();
        assertEquals(1, uploads.size());
        assertEquals("d", uploads.get(0).deviceId());
        assertEquals(3, uploads.get(0).hops());
        assertEquals("p1", uploads.get(0).packet().packetId());
    }

    @Test
    void anInactiveBridgeCannotUploadEvenIfItHasInternet() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        n.setOnline("d", true);
        n.inject("a", packet("p1", 5), "test");
        rounds(n, 4);
        assertEquals(1, n.pendingUploads().size());

        n.setActive("d", false);
        assertTrue(n.pendingUploads().isEmpty());
    }

    @Test
    void uploadOutcomesDecideWhetherACopyIsRetried() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        n.setOnline("d", true);
        n.inject("a", packet("p1", 5), "test");
        rounds(n, 4);

        PendingUpload up = n.pendingUploads().get(0);
        n.recordUpload(up, Outcome.FAILED, Reason.PROCESSING_ERROR, "backend hiccup");
        assertEquals(1, n.pendingUploads().size(), "transient failure keeps the copy queued for retry");
        assertNotEquals(PacketStatus.SETTLED, n.status("p1"));

        n.recordUpload(up, Outcome.SETTLED, null, "ok");
        assertTrue(n.pendingUploads().isEmpty(), "acknowledged copy is not uploaded again");
        assertEquals(PacketStatus.SETTLED, n.status("p1"));
        assertEquals(2, n.trace("p1").orElseThrow().uploads().size());
    }

    @Test
    void rejectedAndDuplicateOutcomesAlsoAcknowledge() {
        for (Outcome o : new Outcome[]{Outcome.REJECTED, Outcome.DUPLICATE_DROPPED}) {
            MeshNetwork n = net(chain(), settings(0, 0, 1));
            n.setOnline("d", true);
            n.inject("a", packet("p1", 5), "test");
            rounds(n, 4);
            n.recordUpload(n.pendingUploads().get(0), o, o == Outcome.REJECTED ? Reason.DECRYPTION_FAILED : null, "x");
            assertTrue(n.pendingUploads().isEmpty(), o.name());
            assertEquals(PacketStatus.valueOf(o.name()), n.status("p1"));
        }
    }

    // ------------------------------------------------------- loss, delay, partition

    @Test
    void aLostTransmissionNeverDestroysTheOnlyCopyAndIsRetried() {
        MeshNetwork n = net(chain(), settings(1.0, 0, 1)); // every transmission is lost
        n.inject("a", packet("p1", 5), "test");

        GossipResult lossy = n.gossipRound();
        assertEquals(0, lossy.transfers());
        assertTrue(lossy.lost() > 0);
        assertEquals(Set.of("a"), n.holders("p1"), "sender still holds its copy");

        n.updateSettings(settings(0, 0, 1)); // the link recovers
        assertEquals(1, n.gossipRound().transfers());
        assertEquals(Set.of("a", "b"), n.holders("p1"));
    }

    @Test
    void partialLossDeliversEventuallyThroughRetries() {
        MeshNetwork n = net(chain(), settings(0.6, 0, 3));
        n.inject("a", packet("p1", 5), "test");
        rounds(n, 80);
        assertEquals(4, n.holders("p1").size());
    }

    @Test
    void forwardingDelayPostponesArrivalByTheConfiguredRounds() {
        MeshNetwork n = net(chain(), settings(0, 2, 1));
        n.inject("a", packet("p1", 5), "test");

        n.gossipRound(); // sent in round 1, arrives at the end of round 3
        assertFalse(n.holders("p1").contains("b"));
        assertEquals(PacketStatus.IN_MESH, n.status("p1"));
        n.gossipRound();
        assertFalse(n.holders("p1").contains("b"));
        n.gossipRound();
        assertTrue(n.holders("p1").contains("b"));
    }

    @Test
    void inactiveDeviceBreaksThePathUntilReactivated() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        n.setActive("b", false);
        n.inject("a", packet("p1", 5), "test");
        rounds(n, 5);
        assertEquals(Set.of("a"), n.holders("p1"));

        n.setActive("b", true);
        rounds(n, 3);
        assertEquals(Set.of("a", "b", "c", "d"), n.holders("p1"));
    }

    // ------------------------------------------------------------------ expiry

    @Test
    void expiredCopiesAreNeitherForwardedNorUploaded() {
        MeshNetwork n = net(chain(), new MeshSettings(5, 0, 0, 100, 1));
        n.setOnline("d", true);
        n.inject("d", packet("p1", 5), "test");
        assertEquals(1, n.pendingUploads().size());

        clock.advanceSeconds(101);

        assertTrue(n.pendingUploads().isEmpty());
        assertEquals(0, n.gossipRound().transfers());
        assertEquals(PacketStatus.EXPIRED, n.status("p1"));
    }

    // ------------------------------------------------------------- determinism

    @Test
    void sameSeedReproducesTheExactSameRun() {
        List<String> runA = lossyRun(7), runB = lossyRun(7);
        assertEquals(runA, runB);
        assertFalse(runA.isEmpty());
    }

    private List<String> lossyRun(long seed) {
        MeshNetwork n = net(Topology.demo(), settings(0.4, 0, seed));
        n.inject("phone-alice", packet("p1", 6), "test");
        rounds(n, 8);
        return n.trace("p1").orElseThrow().events().stream()
                .map(e -> e.round() + ":" + e.type() + ":" + e.from() + ">" + e.to())
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------- validation

    @Test
    void misuseIsRejected() {
        MeshNetwork n = net(chain(), settings(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> n.inject("nope", packet("p", 1), "t"));
        assertThrows(IllegalArgumentException.class, () -> n.setOnline("a", true), "only bridges have internet");
        assertThrows(IllegalArgumentException.class, () -> n.setActive("nope", true));
        n.setActive("a", false);
        assertThrows(IllegalStateException.class, () -> n.inject("a", packet("p", 1), "t"), "powered-off phone");
    }

    @Test
    void demoTopologyHasThreeBridgesAndFourRelaysAroundTheSender() {
        Topology t = Topology.demo();
        assertEquals(3, t.devices().stream().filter(d -> d.role() == Role.BRIDGE).count());
        assertEquals(8, t.devices().size());
        assertTrue(t.neighbors("relay-1").contains("phone-alice"));
        assertFalse(t.neighbors("phone-alice").contains("relay-2"), "alice only reaches relay-1 directly");
    }
}
