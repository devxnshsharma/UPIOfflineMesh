package com.demo.upimesh.dto;

import com.demo.upimesh.model.IngestEvent;
import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.model.Reason;

import java.math.BigDecimal;
import java.time.Instant;

/** One row of the transaction ledger view: every delivery the backend handled and what it did. */
public record TransactionView(Long id, Instant at, Outcome outcome, Reason reason, String senderVpa, String receiverVpa,
                              BigDecimal amount, String packetHash, String meshPacketId, String bridgeNodeId,
                              int hopCount, Long ledgerEntryId) {
    public static TransactionView of(IngestEvent e) {
        return new TransactionView(e.getId(), e.getAt(), e.getOutcome(), e.getReason(), e.getSenderVpa(),
                e.getReceiverVpa(), e.getAmount(), e.getPacketHash(), e.getMeshPacketId(), e.getBridgeNodeId(),
                e.getHopCount(), e.getLedgerEntryId());
    }
}
