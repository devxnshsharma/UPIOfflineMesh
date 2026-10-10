package com.demo.upimesh.service;

import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.crypto.CryptoException;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.MalformedPayloadException;
import com.demo.upimesh.dto.IngestResult;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.SettlementService.SettlementOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Backend ingestion pipeline for one delivery of one packet:
 *
 *   validate structure -> SHA-256(ciphertext) -> atomic claim -> authenticated decrypt
 *   -> payload + freshness checks -> transactional settlement -> record outcome
 *
 * Outcome policy:
 *  - DUPLICATE_DROPPED  byte-identical packet already claimed: no further work.
 *  - REJECTED           permanent (bad crypto, stale, business rule): claim kept as REJECTED, never retried.
 *  - FAILED             transient (lock conflict, unexpected error): claim RELEASED so the packet is not stuck.
 *
 * Deliberately not @Transactional: each step owns its own transaction so a failure late in the
 * pipeline can still release the claim.
 */
@Service
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    private final HybridCryptoService crypto;
    private final PacketClaimService claims;
    private final SettlementService settlement;
    private final LedgerEntryRepository ledger;
    private final IngestEventRepository events;
    private final MeshProperties props;
    private final Clock clock;

    public BridgeIngestionService(HybridCryptoService crypto, PacketClaimService claims, SettlementService settlement,
                                  LedgerEntryRepository ledger, IngestEventRepository events,
                                  MeshProperties props, Clock clock) {
        this.crypto = crypto;
        this.claims = claims;
        this.settlement = settlement;
        this.ledger = ledger;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, int hopCount) {
        if (!wellFormed(packet)) {
            IngestResult r = IngestResult.rejected("n/a", Reason.MALFORMED_PACKET, "Packet is missing required fields");
            audit(r, packet, bridgeNodeId, hopCount, null, null, null, null);
            return r;
        }

        String hash = crypto.sha256Hex(packet.ciphertext());
        PacketClaimService.Claim claim = claims.claim(hash, packet.packetId(), bridgeNodeId, hopCount);
        if (!claim.first()) {
            IngestResult r = IngestResult.duplicate(hash, claim.existingState());
            LedgerEntry settled = ledger.findByPacketHash(hash).orElse(null);
            audit(r, packet, bridgeNodeId, hopCount, settled == null ? null : settled.getSender().getVpa(),
                    settled == null ? null : settled.getReceiver().getVpa(),
                    settled == null ? null : settled.getAmount(), settled == null ? null : settled.getId());
            log.info("DUPLICATE packet={} bridge={} dropped", hash.substring(0, 12), bridgeNodeId);
            return r;
        }

        try {
            return process(packet, hash, bridgeNodeId, hopCount);
        } catch (RuntimeException e) {
            claims.release(hash);
            Reason reason = e instanceof TransientDataAccessException ? Reason.CONCURRENCY_CONFLICT : Reason.PROCESSING_ERROR;
            log.error("Packet {} failed ({}); claim released, bridge may retry", hash.substring(0, 12), e.toString());
            IngestResult r = IngestResult.failed(hash, reason, "Temporary processing failure; retry the same packet");
            audit(r, packet, bridgeNodeId, hopCount, null, null, null, null);
            return r;
        }
    }

    private IngestResult process(MeshPacket packet, String hash, String bridge, int hops) {
        PaymentInstruction ins;
        try {
            ins = crypto.decrypt(packet.ciphertext());
        } catch (MalformedPayloadException e) {
            return reject(packet, hash, bridge, hops, Reason.MALFORMED_PAYLOAD, "Payload is not a payment instruction", null);
        } catch (CryptoException e) {
            log.warn("Packet {} failed authenticated decryption: {}", hash.substring(0, 12), e.getMessage());
            return reject(packet, hash, bridge, hops, Reason.DECRYPTION_FAILED,
                    "Authenticated decryption failed (tampered, truncated or wrong key)", null);
        }

        if (isBlank(ins.senderVpa()) || isBlank(ins.receiverVpa()) || isBlank(ins.nonce())
                || ins.amount() == null || ins.signedAt() == null) {
            return reject(packet, hash, bridge, hops, Reason.MALFORMED_PAYLOAD, "Payment instruction is incomplete", null);
        }

        long ageSeconds = (clock.millis() - ins.signedAt()) / 1000;
        if (ageSeconds > props.freshnessWindowSeconds()) {
            return reject(packet, hash, bridge, hops, Reason.STALE_TIMESTAMP,
                    "Payment signed " + ageSeconds + "s ago; window is " + props.freshnessWindowSeconds() + "s", ins);
        }
        if (ageSeconds < -props.clockSkewSeconds()) {
            return reject(packet, hash, bridge, hops, Reason.FUTURE_TIMESTAMP,
                    "Payment is signed in the future beyond the allowed clock skew", ins);
        }

        SettlementOutcome outcome = settleWithRetry(ins, hash, bridge, hops);
        if (outcome.rejection() != null) {
            return reject(packet, hash, bridge, hops, outcome.rejection(), describe(outcome.rejection()), ins);
        }
        LedgerEntry entry = outcome.entry();
        IngestResult r = IngestResult.settled(hash, entry.getId());
        audit(r, packet, bridge, hops, ins.senderVpa(), ins.receiverVpa(), ins.amount(), entry.getId());
        return r;
    }

    private SettlementOutcome settleWithRetry(PaymentInstruction ins, String hash, String bridge, int hops) {
        for (int attempt = 1; ; attempt++) {
            try {
                return settlement.settle(ins, hash, bridge, hops);
            } catch (DataIntegrityViolationException e) {
                // Lost a race: another packet carrying the same nonce committed first. Whole tx rolled back.
                if (ledger.existsByNonce(ins.nonce())) return SettlementOutcome.rejected(Reason.NONCE_REUSED);
                throw e;
            } catch (TransientDataAccessException e) {
                if (attempt >= props.settlementRetries()) throw e;
                log.debug("Transient settlement conflict (attempt {}): {}", attempt, e.toString());
            }
        }
    }

    private IngestResult reject(MeshPacket packet, String hash, String bridge, int hops,
                                Reason reason, String message, PaymentInstruction ins) {
        claims.reject(hash, reason);
        IngestResult r = IngestResult.rejected(hash, reason, message);
        audit(r, packet, bridge, hops, ins == null ? null : ins.senderVpa(),
                ins == null ? null : ins.receiverVpa(), ins == null ? null : ins.amount(), null);
        log.warn("REJECTED packet={} reason={} bridge={}", hash.substring(0, Math.min(12, hash.length())), reason, bridge);
        return r;
    }

    private void audit(IngestResult r, MeshPacket packet, String bridge, int hops, String sender, String receiver,
                       java.math.BigDecimal amount, Long ledgerId) {
        try {
            events.save(new IngestEvent(clock.instant(), r.outcome(), r.reason(), r.packetHash(),
                    packet == null ? null : packet.packetId(), bridge, hops, sender, receiver, amount,
                    ledgerId != null ? ledgerId : r.ledgerEntryId()));
        } catch (RuntimeException e) {
            log.warn("Could not write audit event ({}); outcome {} still stands", e.toString(), r.outcome());
        }
    }

    private static boolean wellFormed(MeshPacket p) {
        return p != null && !isBlank(p.packetId()) && !isBlank(p.ciphertext()) && p.ttl() >= 0 && p.createdAt() != null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String describe(Reason r) {
        return switch (r) {
            case INVALID_AMOUNT -> "Amount must be positive, at most 2 decimals, and within the cap";
            case SAME_ACCOUNT -> "Sender and receiver must be different accounts";
            case NONCE_REUSED -> "This payment (nonce) was already settled under another packet";
            case UNKNOWN_SENDER -> "Sender account does not exist";
            case UNKNOWN_RECEIVER -> "Receiver account does not exist";
            case INSUFFICIENT_FUNDS -> "Sender balance is too low at settlement time";
            default -> r.name();
        };
    }
}
