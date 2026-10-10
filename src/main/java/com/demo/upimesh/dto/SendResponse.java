package com.demo.upimesh.dto;

import java.util.List;

/** What the sender learns after handing a packet to the mesh. Never contains the plaintext payment. */
public record SendResponse(String packetId, SendMode mode, int ttl, String injectedAt, List<String> replicatedTo,
                           String fingerprint, String ciphertextPreview, int ciphertextLength, long createdAt) {}
