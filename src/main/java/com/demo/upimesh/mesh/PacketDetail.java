package com.demo.upimesh.mesh;

import com.demo.upimesh.model.Outcome;

import java.util.List;

/**
 * Everything the packet-inspection panel shows. The ciphertext is exactly what every relay holds: opaque Base64
 * that only the backend's private key can open. No payment field is ever part of this view.
 */
public record PacketDetail(String packetId, PacketStatus status, String fingerprint, String ciphertextPreview,
                           String ciphertext, int ciphertextLength, long createdAt, int initialTtl, String origin, Outcome finalOutcome,
                           List<MeshSnapshot.DeviceCopy> holders, List<MeshEvent> events, List<PacketTrace.Upload> uploads) {
}
