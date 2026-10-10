package com.demo.upimesh.mesh;

import com.demo.upimesh.crypto.Hashing;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.model.Reason;

import java.time.Clock;
import java.util.*;

/**
 * Deterministic store-and-forward gossip network (pure Java, no Spring, no crypto keys).
 *
 * One {@link #gossipRound()} = one hop. Every active device offers each packet it holds (TTL &gt; 0, not
 * expired) to each active neighbour that does not have it. A transmission may be lost (seeded RNG): the
 * sender keeps its copy and retries next round, so a failed forward never destroys the only copy.
 * The receiver's copy has TTL-1. Bridges with internet upload their copies through {@link #pendingUploads()}
 * / {@link #recordUpload}; the network never decrypts anything.
 *
 * Thread-safety: one coarse lock. ponytail: per-device locks if the simulation ever needs throughput.
 */
public class MeshNetwork {

    private static final int RECENT_EVENTS = 300;

    private record Arrival(int dueRound, String to, StoredPacket copy, StoredPacket source) {}

    private final Topology topology;
    private final Clock clock;
    private MeshSettings settings;
    private Random rng;

    private final Map<String, VirtualDevice> devices = new TreeMap<>();
    private final Map<String, PacketTrace> traces = new LinkedHashMap<>();
    private final Deque<MeshEvent> recent = new ArrayDeque<>();
    private final List<Arrival> inFlight = new ArrayList<>();
    private final List<MeshSnapshot.Transfer> lastTransfers = new ArrayList<>();

    private int round;
    private long seq;
    private long forwardedTotal, lostTotal, suppressedTotal;

    public MeshNetwork(Topology topology, MeshSettings settings, Clock clock) {
        this.topology = topology;
        this.settings = settings;
        this.clock = clock;
        this.rng = new Random(settings.seed());
        for (Topology.DeviceDef d : topology.devices()) devices.put(d.id(), new VirtualDevice(d));
    }

    // ------------------------------------------------------------------ control

    public synchronized MeshSettings settings() { return settings; }

    public synchronized void updateSettings(MeshSettings next) {
        if (next.seed() != settings.seed()) rng = new Random(next.seed());
        settings = next;
    }

    /** Bridge connectivity: does this device currently reach the backend? */
    public synchronized void setOnline(String deviceId, boolean online) {
        VirtualDevice d = require(deviceId);
        if (d.role() != Role.BRIDGE) throw new IllegalArgumentException(deviceId + " is a relay; only bridges can have internet");
        if (d.online != online) event(MeshEvent.Type.DEVICE, null, deviceId, null, online ? "internet connected" : "internet lost");
        d.online = online;
    }

    /** Power / range: an inactive device neither forwards, receives nor uploads (its stored copies are kept). */
    public synchronized void setActive(String deviceId, boolean active) {
        VirtualDevice d = require(deviceId);
        if (d.active != active) event(MeshEvent.Type.DEVICE, null, deviceId, null, active ? "back in range" : "left the mesh");
        d.active = active;
    }

    // ---------------------------------------------------------------- injection

    /** The sender hands a freshly encrypted packet to a device (its own phone, or any bridge for replication). */
    public synchronized void inject(String deviceId, MeshPacket packet, String note) {
        VirtualDevice d = require(deviceId);
        if (!d.active) throw new IllegalStateException(deviceId + " is not in the mesh");
        PacketTrace trace = traces.get(packet.packetId());
        if (trace == null) {
            trace = new PacketTrace(packet.packetId(), packet.ciphertext(), Hashing.sha256Hex(packet.ciphertext()),
                    packet.createdAt(), packet.ttl(), deviceId);
            traces.put(packet.packetId(), trace);
            event(MeshEvent.Type.CREATED, packet.packetId(), deviceId, null, "payment instruction created on the sender device");
            event(MeshEvent.Type.ENCRYPTED, packet.packetId(), deviceId, null,
                    "AES-256-GCM payload, AES key wrapped with RSA-OAEP (" + packet.ciphertext().length() + " base64 chars)");
        }
        if (d.store.putIfAbsent(packet.packetId(), new StoredPacket(packet, packet.ttl(), 0, null)) == null) {
            event(MeshEvent.Type.INJECTED, packet.packetId(), null, deviceId, note + ", ttl=" + packet.ttl());
            if (d.role() == Role.BRIDGE) event(MeshEvent.Type.BRIDGE_RECEIVED, packet.packetId(), null, deviceId, "bridge holds a copy");
        }
    }

    // ------------------------------------------------------------------- gossip

    public synchronized GossipResult gossipRound() {
        round++;
        lastTransfers.clear();
        int expiredNow = expireStale();
        int transfers = 0, lost = 0, suppressed = 0;

        // Work from a start-of-round snapshot so a packet moves exactly one hop per round.
        Map<String, List<StoredPacket>> snapshot = new LinkedHashMap<>();
        for (VirtualDevice d : devices.values()) snapshot.put(d.id(), new ArrayList<>(d.store.values()));

        for (VirtualDevice d : devices.values()) {
            if (!d.active) continue;
            for (StoredPacket c : snapshot.get(d.id())) {
                if (c.expired) continue;
                if (c.ttl() <= 0) {
                    logTtlBlock(d, c);
                    continue;
                }
                for (String nid : topology.neighbors(d.id())) {
                    VirtualDevice n = devices.get(nid);
                    if (!n.active || c.forwardedTo.contains(nid)) continue;
                    if (n.store.containsKey(c.packetId()) || receiving(nid, c.packetId())) {
                        c.forwardedTo.add(nid);
                        suppressed++;
                        event(MeshEvent.Type.SUPPRESSED, c.packetId(), d.id(), nid, "neighbour already has it; duplicate transmission avoided");
                        continue;
                    }
                    if (settings.lossProbability() > 0 && rng.nextDouble() < settings.lossProbability()) {
                        lost++;
                        event(MeshEvent.Type.FORWARD_LOST, c.packetId(), d.id(), nid, "transmission lost; copy kept for retry");
                        lastTransfers.add(new MeshSnapshot.Transfer(round, d.id(), nid, c.packetId(), "LOST"));
                        continue;
                    }
                    c.forwardedTo.add(nid);
                    transfers++;
                    StoredPacket copy = new StoredPacket(c.toPacket(), c.ttl() - 1, c.hops() + 1, d.id());
                    inFlight.add(new Arrival(round + settings.delayRounds(), nid, copy, c));
                    event(MeshEvent.Type.FORWARDED, c.packetId(), d.id(), nid,
                            "ttl " + c.ttl() + " -> " + (c.ttl() - 1) + (settings.delayRounds() > 0 ? ", arrives round " + (round + settings.delayRounds()) : ""));
                    lastTransfers.add(new MeshSnapshot.Transfer(round, d.id(), nid, c.packetId(), "SENT"));
                }
            }
        }
        deliverDueArrivals();
        forwardedTotal += transfers;
        lostTotal += lost;
        suppressedTotal += suppressed;
        return new GossipResult(round, transfers, lost, suppressed, expiredNow);
    }

    private boolean receiving(String deviceId, String packetId) {
        for (Arrival a : inFlight) if (a.to().equals(deviceId) && a.copy().packetId().equals(packetId)) return true;
        return false;
    }

    /** TTL only "blocks" a packet if a neighbour is actually still missing it. */
    private void logTtlBlock(VirtualDevice d, StoredPacket c) {
        if (c.ttlBlockLogged) return;
        for (String nid : topology.neighbors(d.id())) {
            VirtualDevice n = devices.get(nid);
            if (n.active && !n.store.containsKey(c.packetId())) {
                c.ttlBlockLogged = true;
                event(MeshEvent.Type.TTL_EXHAUSTED, c.packetId(), d.id(), null, "ttl 0: cannot be forwarded to " + nid + " or any other neighbour");
                return;
            }
        }
    }

    private void deliverDueArrivals() {
        for (Iterator<Arrival> it = inFlight.iterator(); it.hasNext(); ) {
            Arrival a = it.next();
            if (a.dueRound() > round) continue;
            it.remove();
            VirtualDevice n = devices.get(a.to());
            if (!n.active) {
                a.source().forwardedTo.remove(a.to()); // receiver vanished mid-flight: sender will try again later
                continue;
            }
            if (n.store.putIfAbsent(a.copy().packetId(), a.copy()) == null && n.role() == Role.BRIDGE) {
                event(MeshEvent.Type.BRIDGE_RECEIVED, a.copy().packetId(), a.copy().receivedFrom(), n.id(),
                        "bridge holds a copy after " + a.copy().hops() + " hops");
            }
        }
    }

    // ------------------------------------------------------------------ uploads

    /** Copies on bridges that have internet, are in the mesh, are not expired and are not yet acknowledged. */
    public synchronized List<PendingUpload> pendingUploads() {
        expireStale();
        List<PendingUpload> out = new ArrayList<>();
        for (VirtualDevice d : devices.values()) {
            if (d.role() != Role.BRIDGE || !d.online || !d.active) continue;
            for (StoredPacket c : d.store.values()) {
                if (!c.expired && c.uploadState == StoredPacket.UploadState.PENDING) {
                    out.add(new PendingUpload(d.id(), c.toPacket(), c.hops()));
                }
            }
        }
        return out;
    }

    /**
     * Apply the backend's answer. SETTLED / DUPLICATE_DROPPED / REJECTED are final for these bytes, so the
     * copy is acknowledged. FAILED is transient: the copy stays queued and the next flush retries it.
     */
    public synchronized void recordUpload(PendingUpload upload, Outcome outcome, Reason reason, String message) {
        VirtualDevice d = require(upload.deviceId());
        StoredPacket c = d.store.get(upload.packet().packetId());
        if (c == null) throw new IllegalArgumentException("Device does not hold that packet");
        c.uploadAttempts++;
        if (outcome != Outcome.FAILED) c.uploadState = StoredPacket.UploadState.ACKED;
        PacketTrace trace = traces.get(c.packetId());
        trace.add(new PacketTrace.Upload(d.id(), outcome, reason, message, round, clock.millis()));
        event(MeshEvent.Type.UPLOAD_ATTEMPT, c.packetId(), d.id(), "backend", outcome + (reason == null ? "" : " (" + reason + ")"));
    }

    /** Free-form history entry, e.g. a demo attack on a packet. */
    public synchronized void annotate(String packetId, MeshEvent.Type type, String from, String detail) {
        event(type, packetId, from, null, detail);
    }

    // ------------------------------------------------------------------- queries

    /** How many distinct packets this network has seen (the live demo caps this so a shared instance cannot grow without bound). */
    public synchronized int packetCount() { return traces.size(); }

    public synchronized Set<String> holders(String packetId) {
        Set<String> out = new TreeSet<>();
        for (VirtualDevice d : devices.values()) if (d.store.containsKey(packetId)) out.add(d.id());
        return out;
    }

    public synchronized Optional<StoredPacket> copy(String deviceId, String packetId) {
        return Optional.ofNullable(require(deviceId).store.get(packetId));
    }

    public synchronized Optional<PacketTrace> trace(String packetId) {
        return Optional.ofNullable(traces.get(packetId));
    }

    public synchronized PacketStatus status(String packetId) {
        expireStale();
        PacketTrace trace = traces.get(packetId);
        if (trace == null) throw new IllegalArgumentException("Unknown packet " + packetId);
        Outcome fin = trace.finalOutcome();
        if (fin != null) return PacketStatus.valueOf(fin.name());
        boolean live = false, atBridge = false, canProgress = false, ttlBlocked = false;
        for (VirtualDevice d : devices.values()) {
            StoredPacket c = d.store.get(packetId);
            if (c == null || c.expired) continue;
            live = true;
            if (d.role() == Role.BRIDGE && c.uploadState == StoredPacket.UploadState.PENDING) atBridge = true;
            boolean neighbourMissing = topology.neighbors(d.id()).stream().anyMatch(n -> !devices.get(n).store.containsKey(packetId));
            if (neighbourMissing) {
                if (c.ttl() > 0) canProgress = true; else ttlBlocked = true;
            }
        }
        boolean arriving = inFlight.stream().anyMatch(a -> a.copy().packetId().equals(packetId));
        if (atBridge) return PacketStatus.AT_BRIDGE;
        if (arriving) return PacketStatus.IN_MESH;
        if (!live) return PacketStatus.EXPIRED;
        // TTL_EXHAUSTED: the frontier copies have no hops left AND nothing else can still carry it onward.
        return !canProgress && ttlBlocked ? PacketStatus.TTL_EXHAUSTED : PacketStatus.IN_MESH;
    }

    public synchronized List<MeshSnapshot.DeviceView> devices() {
        List<MeshSnapshot.DeviceView> out = new ArrayList<>();
        for (VirtualDevice d : devices.values()) {
            List<MeshSnapshot.CopyView> copies = new ArrayList<>();
            for (StoredPacket c : d.store.values()) {
                copies.add(new MeshSnapshot.CopyView(c.packetId(), c.ttl(), c.hops(), c.receivedFrom(),
                        c.uploadState.name(), c.uploadAttempts, c.expired));
            }
            out.add(new MeshSnapshot.DeviceView(d.id(), d.def.label(), d.role(), d.def.x(), d.def.y(), d.online, d.active,
                    List.copyOf(topology.neighbors(d.id())), copies));
        }
        return out;
    }

    public synchronized MeshSnapshot snapshot(String topologyName) {
        List<MeshSnapshot.PacketView> packets = new ArrayList<>();
        int awaiting = 0;
        for (PacketTrace t : traces.values()) {
            PacketStatus st = status(t.packetId());
            if (st == PacketStatus.IN_MESH || st == PacketStatus.AT_BRIDGE) awaiting++;
            packets.add(new MeshSnapshot.PacketView(t.packetId(), st, t.fingerprint(), t.createdAt(), t.initialTtl(),
                    t.origin(), List.copyOf(holders(t.packetId()))));
        }
        int active = 0, bridges = 0, online = 0;
        for (VirtualDevice d : devices.values()) {
            if (d.active) active++;
            if (d.role() == Role.BRIDGE) { bridges++; if (d.online && d.active) online++; }
        }
        Collections.reverse(packets); // newest first
        return new MeshSnapshot(round, settings, topologyName, senderDeviceId(), devices(), topology.links(), packets,
                List.copyOf(lastTransfers),
                new MeshSnapshot.Counters(devices.size(), active, bridges, online, awaiting, forwardedTotal, lostTotal, suppressedTotal),
                recentEvents(60));
    }

    public synchronized Optional<PacketDetail> detail(String packetId) {
        PacketTrace t = traces.get(packetId);
        if (t == null) return Optional.empty();
        List<MeshSnapshot.DeviceCopy> holders = new ArrayList<>();
        for (VirtualDevice d : devices.values()) {
            StoredPacket c = d.store.get(packetId);
            if (c != null) holders.add(new MeshSnapshot.DeviceCopy(d.id(), d.role(), c.ttl(), c.hops(), c.receivedFrom(),
                    c.uploadState.name(), c.uploadAttempts, c.expired));
        }
        String ct = t.ciphertext();
        return Optional.of(new PacketDetail(t.packetId(), status(packetId), t.fingerprint(),
                ct.length() > 72 ? ct.substring(0, 72) + "..." : ct, ct, ct.length(), t.createdAt(), t.initialTtl(), t.origin(),
                t.finalOutcome(), holders, t.events(), t.uploads()));
    }

    public synchronized List<MeshEvent> recentEvents(int limit) {
        List<MeshEvent> all = new ArrayList<>(recent);
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }

    /** The sender's phone: by convention the first device defined by the topology. */
    public String senderDeviceId() { return topology.devices().get(0).id(); }

    // ------------------------------------------------------------------ internals

    private VirtualDevice require(String id) {
        VirtualDevice d = devices.get(id);
        if (d == null) throw new IllegalArgumentException("Unknown device: " + id);
        return d;
    }

    /** Flag copies older than the packet-expiry policy; expired copies are inert (not forwarded, not uploaded). */
    private int expireStale() {
        long cutoff = clock.millis() - settings.packetExpirySeconds() * 1000;
        int n = 0;
        for (VirtualDevice d : devices.values()) {
            for (StoredPacket c : d.store.values()) {
                if (!c.expired && c.createdAt() < cutoff) {
                    c.expired = true;
                    n++;
                    event(MeshEvent.Type.EXPIRED, c.packetId(), d.id(), null, "older than the " + settings.packetExpirySeconds() + "s packet-expiry policy");
                }
            }
        }
        return n;
    }

    private void event(MeshEvent.Type type, String packetId, String from, String to, String detail) {
        MeshEvent e = new MeshEvent(++seq, clock.millis(), round, type, packetId, from, to, detail);
        recent.addLast(e);
        if (recent.size() > RECENT_EVENTS) recent.removeFirst();
        if (packetId != null) {
            PacketTrace t = traces.get(packetId);
            if (t != null) t.add(e);
        }
    }
}
