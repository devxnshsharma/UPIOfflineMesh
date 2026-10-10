package com.demo.upimesh.dto;

/** What kind of packet the simulated sender creates. */
public enum SendMode {
    /** One valid packet injected at the sender's phone. */
    NORMAL,
    /** Valid packet, also pre-replicated onto every bridge, so several bridges upload identical bytes. */
    DUPLICATE,
    /** A malicious relay flipped one byte of the ciphertext. */
    TAMPERED,
    /** Correctly encrypted, but signed outside the freshness window (stale replay). */
    EXPIRED
}
