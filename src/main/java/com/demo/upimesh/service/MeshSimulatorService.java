package com.demo.upimesh.service;

import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.dto.*;
import com.demo.upimesh.mesh.*;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Outcome;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * The live network the dashboard drives, plus the bridge upload step that connects it to the backend.
 *
 * Bridges only ever handle opaque ciphertext: this class depends on BridgeIngestionService (the same
 * pipeline behind POST /api/bridge/ingest) and never on the decryption key.
 */
@Service
public class MeshSimulatorService {

    private final BridgeIngestionService ingestion;
    private final MeshProperties props;
    private final Clock clock;

    private volatile MeshNetwork network;
    private volatile String topologyName = "demo";

    public MeshSimulatorService(BridgeIngestionService ingestion, MeshProperties props, Clock clock) {
        this.ingestion = ingestion;
        this.props = props;
        this.clock = clock;
        this.network = build("demo");
    }

    private MeshNetwork build(String topology) {
        MeshProperties.Sim sim = props.sim();
        return new MeshNetwork(Topology.preset(topology),
                new MeshSettings(props.defaultTtl(), sim.lossProbability(), sim.delayRounds(), sim.packetExpirySeconds(), sim.seed()),
                clock);
    }

    /** Back to the configured baseline (optionally on another topology). All packets and device states are dropped. */
    public synchronized void reset(String topology) {
        String name = topology == null || topology.isBlank() ? "demo" : topology;
        this.network = build(name);
        this.topologyName = name;
    }

    public MeshSnapshot snapshot() { return network.snapshot(topologyName); }

    public Optional<PacketDetail> detail(String packetId) { return network.detail(packetId); }

    public Optional<PacketTrace> trace(String packetId) { return network.trace(packetId); }

    public MeshSettings settings() { return network.settings(); }

    public MeshSettings updateSettings(SettingsRequest r) {
        MeshSettings cur = network.settings();
        MeshSettings next = new MeshSettings(
                r.defaultTtl() != null ? r.defaultTtl() : cur.defaultTtl(),
                r.lossProbability() != null ? r.lossProbability() : cur.lossProbability(),
                r.delayRounds() != null ? r.delayRounds() : cur.delayRounds(),
                r.packetExpirySeconds() != null ? r.packetExpirySeconds() : cur.packetExpirySeconds(),
                r.seed() != null ? r.seed() : cur.seed());
        network.updateSettings(next);
        return next;
    }

    public MeshSnapshot.DeviceView updateDevice(String id, DeviceUpdateRequest r) {
        if (network.devices().stream().noneMatch(d -> d.id().equals(id))) throw new NotFoundException("Unknown device: " + id);
        if (r.online() != null) network.setOnline(id, r.online());
        if (r.active() != null) network.setActive(id, r.active());
        return network.devices().stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("Unknown device: " + id));
    }

    /** Where the sender's phone lives. */
    public String senderDevice() {
        return network.senderDeviceId();
    }

    public List<String> activeBridgeIds() {
        return network.devices().stream().filter(d -> d.role() == Role.BRIDGE && d.active()).map(MeshSnapshot.DeviceView::id).toList();
    }

    public void inject(String deviceId, MeshPacket packet, String note) {
        MeshNetwork net = network;
        if (net.trace(packet.packetId()).isEmpty() && net.packetCount() >= props.maxPackets()) {
            throw new IllegalStateException("The mesh already holds " + net.packetCount()
                    + " packets (limit " + props.maxPackets() + "). Reset to the baseline to start fresh.");
        }
        net.inject(deviceId, packet, note);
    }

    public void annotate(String packetId, MeshEvent.Type type, String from, String detail) {
        network.annotate(packetId, type, from, detail);
    }

    public GossipResponse gossip(int rounds) {
        List<GossipResult> per = new ArrayList<>();
        for (int i = 0; i < rounds; i++) per.add(network.gossipRound());
        return new GossipResponse(rounds,
                per.stream().mapToInt(GossipResult::transfers).sum(), per.stream().mapToInt(GossipResult::lost).sum(),
                per.stream().mapToInt(GossipResult::suppressed).sum(), per.stream().mapToInt(GossipResult::expired).sum(), per);
    }

    /**
     * Every online bridge uploads every un-acknowledged packet it holds, all at the same instant.
     * The backend's per-packet answer decides whether a bridge keeps (FAILED) or drops (anything else) its copy.
     */
    public FlushResponse flush() {
        MeshNetwork net = network;
        List<PendingUpload> uploads = net.pendingUploads();
        List<Callable<IngestResult>> calls = uploads.stream()
                .<Callable<IngestResult>>map(u -> () -> ingestion.ingest(u.packet(), u.deviceId(), u.hops()))
                .toList();
        List<IngestResult> answers = Parallel.all(calls);

        List<FlushResponse.Upload> rows = new ArrayList<>();
        for (int i = 0; i < uploads.size(); i++) {
            PendingUpload u = uploads.get(i);
            IngestResult r = answers.get(i);
            net.recordUpload(u, r.outcome(), r.reason(), r.message());
            rows.add(new FlushResponse.Upload(u.deviceId(), u.packet().packetId(), r.outcome(), r.reason(), r.ledgerEntryId(), r.message()));
        }
        return new FlushResponse(rows.size(), summarise(answers), rows);
    }

    static Map<Outcome, Long> summarise(List<IngestResult> results) {
        Map<Outcome, Long> m = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) m.put(o, 0L);
        results.forEach(r -> m.merge(r.outcome(), 1L, Long::sum));
        return m.entrySet().stream().filter(e -> e.getValue() > 0)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, () -> new EnumMap<>(Outcome.class)));
    }
}
