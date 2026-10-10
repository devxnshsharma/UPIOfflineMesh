package com.demo.upimesh.mesh;

/** One thing that happened in the mesh (feeds packet history and the activity log). */
public record MeshEvent(long seq, long atMillis, int round, Type type, String packetId, String from, String to, String detail) {

    public enum Type {
        CREATED, ENCRYPTED, INJECTED, FORWARDED, FORWARD_LOST, SUPPRESSED, TTL_EXHAUSTED, EXPIRED,
        BRIDGE_RECEIVED, UPLOAD_ATTEMPT, ATTACK, DEVICE
    }
}
