package com.demo.upimesh.mesh;

import com.demo.upimesh.model.MeshPacket;

/** A packet a bridge is ready to submit to the backend. {@code hops} = distance travelled from the sender. */
public record PendingUpload(String deviceId, MeshPacket packet, int hops) {}
