package com.demo.upimesh.mesh;

/** Where a packet stands in its life, derived from real device stores and upload records. */
public enum PacketStatus {
    /** Still travelling (or waiting for a forwarding opportunity). */
    IN_MESH,
    /** A bridge holds a copy that has not been acknowledged by the backend yet. */
    AT_BRIDGE,
    /** Backend outcomes (first delivery to produce one wins: SETTLED > REJECTED > DUPLICATE_DROPPED). */
    SETTLED, REJECTED, DUPLICATE_DROPPED,
    /** Every live copy has TTL 0 and no bridge holds one: the packet can travel no further. */
    TTL_EXHAUSTED,
    /** Every copy outlived the mesh packet-expiry policy. */
    EXPIRED
}
