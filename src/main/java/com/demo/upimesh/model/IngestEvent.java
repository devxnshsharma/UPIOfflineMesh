package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Audit row for every delivery the backend handled, including duplicates and rejections.
 * This is what the dashboard's transaction ledger shows; {@link LedgerEntry} holds only real money movement.
 */
@Entity
@Table(name = "ingest_events")
public class IngestEvent {

    /** Bridge ids with this prefix belong to networking experiments, not the live demo. */
    public static final String SIM_BRIDGE_PREFIX = "sim-";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Outcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private Reason reason;

    @Column(nullable = false, length = 64)
    private String packetHash;

    private String meshPacketId;
    private String bridgeNodeId;
    private int hopCount;

    // Known only when the payload was decrypted (settled / rejected after decryption / duplicate of a settled one).
    private String senderVpa;
    private String receiverVpa;
    @Column(precision = 19, scale = 2)
    private BigDecimal amount;
    private Long ledgerEntryId;

    @Column(nullable = false)
    private boolean simulation;

    protected IngestEvent() {}

    public IngestEvent(Instant at, Outcome outcome, Reason reason, String packetHash, String meshPacketId,
                       String bridgeNodeId, int hopCount, String senderVpa, String receiverVpa,
                       BigDecimal amount, Long ledgerEntryId) {
        this.at = at;
        this.outcome = outcome;
        this.reason = reason;
        this.packetHash = packetHash;
        this.meshPacketId = meshPacketId;
        this.bridgeNodeId = bridgeNodeId;
        this.hopCount = hopCount;
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.ledgerEntryId = ledgerEntryId;
        this.simulation = bridgeNodeId != null && bridgeNodeId.startsWith(SIM_BRIDGE_PREFIX);
    }

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public Outcome getOutcome() { return outcome; }
    public Reason getReason() { return reason; }
    public String getPacketHash() { return packetHash; }
    public String getMeshPacketId() { return meshPacketId; }
    public String getBridgeNodeId() { return bridgeNodeId; }
    public int getHopCount() { return hopCount; }
    public String getSenderVpa() { return senderVpa; }
    public String getReceiverVpa() { return receiverVpa; }
    public BigDecimal getAmount() { return amount; }
    public Long getLedgerEntryId() { return ledgerEntryId; }
    public boolean isSimulation() { return simulation; }
}
