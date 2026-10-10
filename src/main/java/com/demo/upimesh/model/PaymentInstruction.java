package com.demo.upimesh.model;

import java.math.BigDecimal;

/**
 * The plaintext payment, visible only to the sender and (after decryption) the backend.
 *
 * @param nonce    unique per payment intent - the logical-uniqueness key. Re-encrypting the same
 *                 instruction yields new ciphertext but the same nonce, so it cannot settle twice.
 * @param signedAt epoch millis when the sender created it; lives INSIDE the authenticated
 *                 ciphertext, so relays cannot alter it. Drives the freshness check.
 */
public record PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                                 String nonce, Long signedAt) {}
