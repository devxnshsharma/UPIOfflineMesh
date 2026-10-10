package com.demo.upimesh.model;

/** What the backend did with one delivery of a packet. */
public enum Outcome {
    /** First valid delivery: money moved, one ledger entry written. */
    SETTLED,
    /** A byte-identical copy was already claimed or processed; nothing was done. */
    DUPLICATE_DROPPED,
    /** Permanent: this packet will never settle. Bridges must not retry it. */
    REJECTED,
    /** Transient: processing hit an error and the claim was released. Bridges should retry. */
    FAILED
}
