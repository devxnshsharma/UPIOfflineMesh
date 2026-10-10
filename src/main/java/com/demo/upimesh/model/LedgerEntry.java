package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable record of one settled payment. Written in the same transaction as the debit and credit.
 *
 * Two UNIQUE constraints protect "exactly one financial effect":
 *  - nonce:       the same logical payment cannot settle twice, even repackaged in new ciphertext
 *  - packet_hash: the same bytes cannot settle twice
 */
@Entity
@Table(name = "ledger_entries", uniqueConstraints = {
        @UniqueConstraint(name = "uk_ledger_nonce", columnNames = "nonce"),
        @UniqueConstraint(name = "uk_ledger_packet_hash", columnNames = "packet_hash")})
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String nonce;

    @Column(name = "packet_hash", nullable = false, length = 64)
    private String packetHash;

    @ManyToOne(optional = false)
    @JoinColumn(name = "sender_vpa", nullable = false)
    private Account sender;

    @ManyToOne(optional = false)
    @JoinColumn(name = "receiver_vpa", nullable = false)
    private Account receiver;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Instant signedAt;

    @Column(nullable = false)
    private Instant settledAt;

    @Column(nullable = false)
    private String bridgeNodeId;

    private int hopCount;

    protected LedgerEntry() {}

    public LedgerEntry(String nonce, String packetHash, Account sender, Account receiver, BigDecimal amount,
                       Instant signedAt, String bridgeNodeId, int hopCount) {
        this.nonce = nonce;
        this.packetHash = packetHash;
        this.sender = sender;
        this.receiver = receiver;
        this.amount = amount;
        this.signedAt = signedAt;
        this.settledAt = Instant.now();
        this.bridgeNodeId = bridgeNodeId;
        this.hopCount = hopCount;
    }

    public Long getId() { return id; }
    public String getNonce() { return nonce; }
    public String getPacketHash() { return packetHash; }
    public Account getSender() { return sender; }
    public Account getReceiver() { return receiver; }
    public BigDecimal getAmount() { return amount; }
    public Instant getSignedAt() { return signedAt; }
    public Instant getSettledAt() { return settledAt; }
    public String getBridgeNodeId() { return bridgeNodeId; }
    public int getHopCount() { return hopCount; }
}
