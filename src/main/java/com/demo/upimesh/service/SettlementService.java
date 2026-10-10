package com.demo.upimesh.service;

import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The only place balances change. One database transaction covers: lock both accounts, check the
 * rules, debit, credit, insert the ledger entry, mark the packet SETTLED. If anything throws, all
 * of it rolls back - there is no partial debit or credit.
 *
 * Business rejections (unknown account, insufficient funds, ...) are returned, not thrown, and
 * change nothing. Infrastructure errors propagate so the caller can retry or release the claim.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    /** Exactly one of {@code entry} (settled) or {@code rejection} is set. */
    public record SettlementOutcome(LedgerEntry entry, Reason rejection) {
        static SettlementOutcome settled(LedgerEntry e) { return new SettlementOutcome(e, null); }
        static SettlementOutcome rejected(Reason r) { return new SettlementOutcome(null, r); }
    }

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final PacketRecordRepository packets;
    private final MeshProperties props;

    public SettlementService(AccountRepository accounts, LedgerEntryRepository ledger,
                             PacketRecordRepository packets, MeshProperties props) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.packets = packets;
        this.props = props;
    }

    @Transactional
    public SettlementOutcome settle(PaymentInstruction ins, String packetHash, String bridgeNodeId, int hopCount) {
        if (!validAmount(ins.amount())) return SettlementOutcome.rejected(Reason.INVALID_AMOUNT);
        if (ins.senderVpa().equals(ins.receiverVpa())) return SettlementOutcome.rejected(Reason.SAME_ACCOUNT);
        if (ledger.existsByNonce(ins.nonce())) return SettlementOutcome.rejected(Reason.NONCE_REUSED);

        // Lock in a fixed (alphabetical) order so two opposite transfers can never deadlock.
        boolean senderFirst = ins.senderVpa().compareTo(ins.receiverVpa()) < 0;
        Account first = accounts.findForUpdate(senderFirst ? ins.senderVpa() : ins.receiverVpa()).orElse(null);
        Account second = accounts.findForUpdate(senderFirst ? ins.receiverVpa() : ins.senderVpa()).orElse(null);
        Account sender = senderFirst ? first : second;
        Account receiver = senderFirst ? second : first;
        if (sender == null) return SettlementOutcome.rejected(Reason.UNKNOWN_SENDER);
        if (receiver == null) return SettlementOutcome.rejected(Reason.UNKNOWN_RECEIVER);

        if (sender.getBalance().compareTo(ins.amount()) < 0) {
            return SettlementOutcome.rejected(Reason.INSUFFICIENT_FUNDS);
        }

        sender.setBalance(sender.getBalance().subtract(ins.amount()));
        receiver.setBalance(receiver.getBalance().add(ins.amount()));
        // Flushes the balance updates and the INSERT together; a UNIQUE violation aborts all of it.
        LedgerEntry entry = ledger.saveAndFlush(new LedgerEntry(ins.nonce(), packetHash, sender, receiver,
                ins.amount(), Instant.ofEpochMilli(ins.signedAt()), bridgeNodeId, hopCount));

        PacketRecord record = packets.findByPacketHash(packetHash)
                .orElseThrow(() -> new IllegalStateException("settle() called for an unclaimed packet"));
        record.finish(PacketRecord.State.SETTLED, null, entry.getId(), Instant.now());

        log.info("SETTLED ledger={} packet={} bridge={} hops={}", entry.getId(), packetHash.substring(0, 12), bridgeNodeId, hopCount);
        return SettlementOutcome.settled(entry);
    }

    /** Positive, at most paise precision, within the configured cap. */
    private boolean validAmount(BigDecimal amount) {
        return amount.signum() > 0
                && amount.stripTrailingZeros().scale() <= 2
                && amount.compareTo(props.maxAmount()) <= 0;
    }
}
