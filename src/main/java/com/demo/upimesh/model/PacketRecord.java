package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Persistent idempotency claim: one row per distinct ciphertext fingerprint.
 *
 * The UNIQUE constraint on packet_hash is the authoritative "only one delivery wins" guard;
 * the in-memory map in PacketClaimService is just a fast path in front of it.
 *
 * State machine: PROCESSING -> SETTLED | REJECTED (both final).
 * A transient failure deletes the PROCESSING row so the packet can be retried.
 */
@Entity
@Table(name = "packet_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_packet_records_hash", columnNames = "packet_hash"))
public class PacketRecord {

    public enum State { PROCESSING, SETTLED, REJECTED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "packet_hash", nullable = false, length = 64)
    private String packetHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Reason reason;

    private String meshPacketId;
    private String firstBridgeNodeId;
    private int hopCount;

    @Column(nullable = false)
    private Instant claimedAt;
    private Instant completedAt;
    private Long ledgerEntryId;

    protected PacketRecord() {}

    public PacketRecord(String packetHash, String meshPacketId, String bridgeNodeId, int hopCount, Instant now) {
        this.packetHash = packetHash;
        this.meshPacketId = meshPacketId;
        this.firstBridgeNodeId = bridgeNodeId;
        this.hopCount = hopCount;
        this.state = State.PROCESSING;
        this.claimedAt = now;
    }

    public void finish(State finalState, Reason why, Long ledgerId, Instant now) {
        this.state = finalState;
        this.reason = why;
        this.ledgerEntryId = ledgerId;
        this.completedAt = now;
    }

    public Long getId() { return id; }
    public String getPacketHash() { return packetHash; }
    public State getState() { return state; }
    public Reason getReason() { return reason; }
    public String getMeshPacketId() { return meshPacketId; }
    public String getFirstBridgeNodeId() { return firstBridgeNodeId; }
    public int getHopCount() { return hopCount; }
    public Instant getClaimedAt() { return claimedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getLedgerEntryId() { return ledgerEntryId; }
}
