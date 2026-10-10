package com.demo.upimesh.service;

import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.model.PacketRecord;
import com.demo.upimesh.model.PacketRecordRepository;
import com.demo.upimesh.model.Reason;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atomic "who owns this ciphertext?" decision, in two layers:
 *
 *  1. In-memory {@code putIfAbsent} (the JVM-local equivalent of Redis SET NX): cheap fast path that
 *     stops a duplicate storm before it reaches the database.
 *  2. A row in packet_records with a UNIQUE packet_hash, inserted in its own committed transaction.
 *     This is the authoritative guard: it survives cache eviction and would work across replicas.
 *
 * A claim is either completed (final state recorded) or released (transient failure) - never abandoned.
 */
@Service
public class PacketClaimService {

    private static final Logger log = LoggerFactory.getLogger(PacketClaimService.class);

    /** @param existingState state of the winning claim; null while it is still being written. */
    public record Claim(boolean first, PacketRecord.State existingState) {}

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final PacketRecordRepository packets;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeshProperties props;

    public PacketClaimService(PacketRecordRepository packets, PlatformTransactionManager txManager,
                              Clock clock, MeshProperties props) {
        this.packets = packets;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.props = props;
    }

    /** Orphaned PROCESSING rows can only exist if a previous run died mid-flight (file-backed H2). */
    @PostConstruct
    void recoverOrphans() {
        long released = packets.deleteByState(PacketRecord.State.PROCESSING);
        if (released > 0) log.warn("Released {} orphaned PROCESSING claims from a previous run", released);
    }

    public Claim claim(String hash, String meshPacketId, String bridgeNodeId, int hopCount) {
        if (seen.putIfAbsent(hash, clock.instant()) != null) {
            return lost(hash);
        }
        try {
            tx.executeWithoutResult(s ->
                    packets.saveAndFlush(new PacketRecord(hash, meshPacketId, bridgeNodeId, hopCount, clock.instant())));
            return new Claim(true, null);
        } catch (DataIntegrityViolationException alreadyClaimed) {
            return lost(hash); // the DB says someone else owns it (cache was evicted, or another instance)
        } catch (RuntimeException e) {
            seen.remove(hash);
            throw e;
        }
    }

    private Claim lost(String hash) {
        return new Claim(false, packets.findByPacketHash(hash).map(PacketRecord::getState).orElse(null));
    }

    /** Record a permanent rejection (no money moved). */
    public void reject(String hash, Reason reason) {
        tx.executeWithoutResult(s -> packets.findByPacketHash(hash)
                .ifPresent(r -> r.finish(PacketRecord.State.REJECTED, reason, null, clock.instant())));
    }

    /** Give the packet back after a transient failure so any bridge can retry it. */
    public void release(String hash) {
        try {
            tx.executeWithoutResult(s -> packets.deleteByPacketHashAndState(hash, PacketRecord.State.PROCESSING));
        } finally {
            seen.remove(hash);
        }
    }

    public void clear() {
        seen.clear();
    }

    @Scheduled(fixedDelay = 60_000)
    void housekeeping() {
        Instant now = clock.instant();
        Instant cacheCutoff = now.minusSeconds(props.idempotencyTtlSeconds());
        seen.entrySet().removeIf(e -> e.getValue().isBefore(cacheCutoff));

        Instant stuckCutoff = now.minusSeconds(props.processingTimeoutSeconds());
        for (PacketRecord stuck : packets.findByStateAndClaimedAtBefore(PacketRecord.State.PROCESSING, stuckCutoff)) {
            log.warn("Releasing claim stuck in PROCESSING since {}", stuck.getClaimedAt());
            release(stuck.getPacketHash());
        }
    }
}
