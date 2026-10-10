package com.demo.upimesh.mesh;

import com.demo.upimesh.model.MeshPacket;

import java.util.Set;
import java.util.TreeSet;

/** One device's copy of a packet: what it holds, how many hops are left, and what it has already done with it. */
public final class StoredPacket {

    public enum UploadState { PENDING, ACKED }

    private final String packetId;
    private final String ciphertext;
    private final long createdAt;
    private final int ttl;
    private final int hops;
    private final String receivedFrom;

    /** Neighbours that already received (or already had) this packet from us: never offered again. */
    final Set<String> forwardedTo = new TreeSet<>();
    boolean expired;
    boolean ttlBlockLogged;
    UploadState uploadState = UploadState.PENDING;
    int uploadAttempts;

    StoredPacket(MeshPacket packet, int ttl, int hops, String receivedFrom) {
        this.packetId = packet.packetId();
        this.ciphertext = packet.ciphertext();
        this.createdAt = packet.createdAt();
        this.ttl = ttl;
        this.hops = hops;
        this.receivedFrom = receivedFrom;
        if (receivedFrom != null) forwardedTo.add(receivedFrom); // never send it straight back
    }

    public String packetId() { return packetId; }
    public String ciphertext() { return ciphertext; }
    public long createdAt() { return createdAt; }
    public int ttl() { return ttl; }
    public int hops() { return hops; }
    public String receivedFrom() { return receivedFrom; }
    public boolean expired() { return expired; }
    public UploadState uploadState() { return uploadState; }
    public int uploadAttempts() { return uploadAttempts; }

    MeshPacket toPacket() { return new MeshPacket(packetId, ttl, createdAt, ciphertext); }
}
