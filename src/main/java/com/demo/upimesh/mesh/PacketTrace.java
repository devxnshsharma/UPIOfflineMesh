package com.demo.upimesh.mesh;

import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.model.Reason;

import java.util.ArrayList;
import java.util.List;

/** Complete history of one packet: every event and every bridge upload attempt. */
public final class PacketTrace {

    public record Upload(String deviceId, Outcome outcome, Reason reason, String message, int round, long atMillis) {}

    private static final int MAX_EVENTS = 500;

    private final String packetId;
    private final String ciphertext;
    private final String fingerprint;
    private final long createdAt;
    private final int initialTtl;
    private final String origin;
    private final List<MeshEvent> events = new ArrayList<>();
    private final List<Upload> uploads = new ArrayList<>();

    PacketTrace(String packetId, String ciphertext, String fingerprint, long createdAt, int initialTtl, String origin) {
        this.packetId = packetId;
        this.ciphertext = ciphertext;
        this.fingerprint = fingerprint;
        this.createdAt = createdAt;
        this.initialTtl = initialTtl;
        this.origin = origin;
    }

    void add(MeshEvent e) {
        if (events.size() < MAX_EVENTS) events.add(e);
    }

    void add(Upload u) { uploads.add(u); }

    public String packetId() { return packetId; }
    public String ciphertext() { return ciphertext; }
    public String fingerprint() { return fingerprint; }
    public long createdAt() { return createdAt; }
    public int initialTtl() { return initialTtl; }
    public String origin() { return origin; }
    public List<MeshEvent> events() { return List.copyOf(events); }
    public List<Upload> uploads() { return List.copyOf(uploads); }

    /** SETTLED beats REJECTED beats DUPLICATE_DROPPED; null while no bridge got a final answer. */
    public Outcome finalOutcome() {
        Outcome best = null;
        for (Upload u : uploads) {
            if (u.outcome() == Outcome.SETTLED) return Outcome.SETTLED;
            if (u.outcome() == Outcome.REJECTED) best = Outcome.REJECTED;
            else if (u.outcome() == Outcome.DUPLICATE_DROPPED && best == null) best = Outcome.DUPLICATE_DROPPED;
        }
        return best;
    }
}
