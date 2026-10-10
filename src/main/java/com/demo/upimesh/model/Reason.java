package com.demo.upimesh.model;

/** Why a packet was rejected (permanent) or failed (transient). */
public enum Reason {
    MALFORMED_PACKET, DECRYPTION_FAILED, MALFORMED_PAYLOAD,
    INVALID_AMOUNT, SAME_ACCOUNT, STALE_TIMESTAMP, FUTURE_TIMESTAMP,
    UNKNOWN_SENDER, UNKNOWN_RECEIVER, INSUFFICIENT_FUNDS, NONCE_REUSED,
    /** Transient: lock timeout / optimistic-lock conflict survived every retry. */
    CONCURRENCY_CONFLICT,
    /** Transient: unexpected error during processing. */
    PROCESSING_ERROR
}
