package com.demo.upimesh.service;

import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.dto.*;
import com.demo.upimesh.mesh.MeshEvent;
import com.demo.upimesh.mesh.PacketTrace;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The simulated sender phone (builds, encrypts and injects packets) and the simulated attackers
 * (tamper / stale / replay). On a real phone this class would be the Android app; the encryption
 * and injection boundary is explicit: past {@code mesh.inject} everything is opaque ciphertext.
 */
@Service
public class DemoService {

    private final HybridCryptoService crypto;
    private final ServerKeyHolder serverKey;
    private final MeshSimulatorService mesh;
    private final BridgeIngestionService ingestion;
    private final MeshProperties props;
    private final Clock clock;

    /** What the SENDER remembers about each packet it made (its own plaintext). Never given to relays. */
    private final Map<String, PaymentInstruction> outbox = new ConcurrentHashMap<>();

    public DemoService(HybridCryptoService crypto, ServerKeyHolder serverKey, MeshSimulatorService mesh,
                       BridgeIngestionService ingestion, MeshProperties props, Clock clock) {
        this.crypto = crypto;
        this.serverKey = serverKey;
        this.mesh = mesh;
        this.ingestion = ingestion;
        this.props = props;
        this.clock = clock;
    }

    public void reset() {
        outbox.clear();
    }

    public SendResponse send(DemoSendRequest req) {
        SendMode mode = req.mode() == null ? SendMode.NORMAL : req.mode();
        int ttl = req.ttl() != null ? req.ttl() : mesh.settings().defaultTtl();
        String start = req.startDevice() != null && !req.startDevice().isBlank() ? req.startDevice() : mesh.senderDevice();

        long signedAt = clock.millis();
        if (mode == SendMode.EXPIRED) signedAt -= (props.freshnessWindowSeconds() + 3600) * 1000;
        PaymentInstruction instruction = new PaymentInstruction(req.senderVpa(), req.receiverVpa(), req.amount(),
                UUID.randomUUID().toString(), signedAt);

        MeshPacket packet = wrap(instruction, ttl);
        outbox.put(packet.packetId(), instruction);
        if (mode == SendMode.TAMPERED) {
            packet = new MeshPacket(packet.packetId(), packet.ttl(), packet.createdAt(), crypto.flipOneByte(packet.ciphertext()));
        }
        return inject(packet, start, mode);
    }

    private MeshPacket wrap(PaymentInstruction instruction, int ttl) {
        return new MeshPacket(UUID.randomUUID().toString(), ttl, clock.millis(),
                crypto.encrypt(instruction, serverKey.getPublicKey()));
    }

    private SendResponse inject(MeshPacket packet, String start, SendMode mode) {
        mesh.inject(start, packet, "mode " + mode);
        List<String> replicated = List.of();
        if (mode == SendMode.DUPLICATE) {
            replicated = mesh.activeBridgeIds().stream().filter(b -> !b.equals(start)).toList();
            replicated.forEach(b -> mesh.inject(b, packet, "pre-replicated copy (duplicate mode)"));
        }
        switch (mode) {
            case TAMPERED -> mesh.annotate(packet.packetId(), MeshEvent.Type.ATTACK, start,
                    "demo attack: a malicious relay flipped one byte of the ciphertext");
            case EXPIRED -> mesh.annotate(packet.packetId(), MeshEvent.Type.ATTACK, start,
                    "demo attack: payment was signed outside the " + props.freshnessWindowSeconds() + "s freshness window");
            default -> { }
        }
        String ct = packet.ciphertext();
        return new SendResponse(packet.packetId(), mode, packet.ttl(), start, replicated,
                crypto.sha256Hex(ct), ct.substring(0, Math.min(64, ct.length())) + "...", ct.length(), packet.createdAt());
    }

    /**
     * Replay attacks on a packet already in the mesh.
     * IDENTICAL: the same bytes hit the backend {@code times} times at once. REPACKAGED: the same logical
     * payment (same nonce) is re-encrypted into a new packet and injected next to the original.
     */
    public ReplayResponse replay(ReplayRequest req) {
        PacketTrace trace = mesh.trace(req.packetId())
                .orElseThrow(() -> new NotFoundException("Unknown packet: " + req.packetId()));
        int times = req.times() == null ? 1 : req.times();

        if (req.kind() == ReplayRequest.Kind.IDENTICAL) {
            MeshPacket same = new MeshPacket(trace.packetId(), trace.initialTtl(), trace.createdAt(), trace.ciphertext());
            List<Callable<IngestResult>> calls = new java.util.ArrayList<>();
            for (int i = 1; i <= times; i++) {
                String node = "replay-" + i;
                calls.add(() -> ingestion.ingest(same, node, 0));
            }
            List<IngestResult> results = Parallel.all(calls);
            mesh.annotate(trace.packetId(), MeshEvent.Type.ATTACK, null,
                    "demo attack: identical bytes submitted " + times + "x concurrently to the backend");
            return new ReplayResponse(req.kind(), times, MeshSimulatorService.summarise(results), results, null);
        }

        PaymentInstruction original = outbox.get(trace.packetId());
        if (original == null) {
            throw new IllegalStateException("The sender no longer remembers this payment (was the demo reset?)");
        }
        MeshPacket again = wrap(original, trace.initialTtl());
        outbox.put(again.packetId(), original);
        SendResponse injected = inject(again, trace.origin(), SendMode.NORMAL);
        mesh.annotate(again.packetId(), MeshEvent.Type.ATTACK, trace.origin(),
                "demo attack: same payment re-encrypted (new ciphertext, same nonce) - repackaged copy of " + trace.packetId().substring(0, 8));
        return new ReplayResponse(req.kind(), 1, Map.of(), List.of(), injected);
    }
}
