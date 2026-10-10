package com.demo.upimesh.mesh;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A simulated phone. {@code active} = powered on and in the mesh; {@code online} = has internet right now
 * (only meaningful for BRIDGE devices). Forwarding needs {@code active}; uploading needs both.
 * Not thread-safe on its own: every access goes through {@link MeshNetwork}'s lock.
 */
final class VirtualDevice {
    final Topology.DeviceDef def;
    boolean active = true;
    boolean online = false;
    final Map<String, StoredPacket> store = new LinkedHashMap<>();

    VirtualDevice(Topology.DeviceDef def) {
        this.def = def;
    }

    String id() { return def.id(); }
    Role role() { return def.role(); }
}
