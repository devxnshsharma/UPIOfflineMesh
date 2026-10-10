package com.demo.upimesh.dto;

import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.model.PacketRecord;
import com.demo.upimesh.model.Reason;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Structured answer to one packet delivery (also the JSON body of POST /api/bridge/ingest). */
public record IngestResult(Outcome outcome, Reason reason, String packetHash, Long ledgerEntryId, String message) {

    /** Only transient failures are worth retrying; everything else is final for these bytes. */
    @JsonProperty("retryable")
    public boolean retryable() {
        return outcome == Outcome.FAILED;
    }

    public static IngestResult settled(String hash, long ledgerEntryId) {
        return new IngestResult(Outcome.SETTLED, null, hash, ledgerEntryId, "Payment settled");
    }

    public static IngestResult duplicate(String hash, PacketRecord.State existing) {
        String how = existing == null ? "is still being processed" : "was already " + existing;
        return new IngestResult(Outcome.DUPLICATE_DROPPED, null, hash, null,
                "Byte-identical packet " + how + "; no financial effect");
    }

    public static IngestResult rejected(String hash, Reason reason, String message) {
        return new IngestResult(Outcome.REJECTED, reason, hash, null, message);
    }

    public static IngestResult failed(String hash, Reason reason, String message) {
        return new IngestResult(Outcome.FAILED, reason, hash, null, message);
    }
}
