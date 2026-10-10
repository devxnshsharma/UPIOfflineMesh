package com.demo.upimesh.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The over-the-wire packet that hops phone to phone.
 *
 * Relays can read the outer fields (packetId, ttl, createdAt) for forwarding and de-duplication
 * but only ever see {@code ciphertext} as opaque Base64. Outer fields are NOT authenticated: a
 * malicious relay may rewrite them, which is why the backend trusts only the ciphertext hash
 * and the fields inside the authenticated payload.
 */
public record MeshPacket(
        @NotBlank String packetId,
        @Min(0) int ttl,
        @NotNull Long createdAt,
        @NotBlank String ciphertext) {}
