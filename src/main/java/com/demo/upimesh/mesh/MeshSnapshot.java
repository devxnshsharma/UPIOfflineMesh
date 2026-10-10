package com.demo.upimesh.mesh;

import java.util.List;

/** Read-only picture of the whole network at one instant (the JSON of GET /api/mesh/state). */
public record MeshSnapshot(int round, MeshSettings settings, String topology, String senderDevice, List<DeviceView> devices, List<Topology.Link> links,
                           List<PacketView> packets, List<Transfer> lastTransfers, Counters counters,
                           List<MeshEvent> events) {

    public record CopyView(String packetId, int ttl, int hops, String receivedFrom, String uploadState,
                           int uploadAttempts, boolean expired) {}

    public record DeviceCopy(String deviceId, Role role, int ttl, int hops, String receivedFrom, String uploadState,
                             int uploadAttempts, boolean expired) {}

    public record DeviceView(String id, String label, Role role, int x, int y, boolean online, boolean active,
                             List<String> neighbors, List<CopyView> copies) {}

    public record PacketView(String packetId, PacketStatus status, String fingerprint, long createdAt, int initialTtl,
                             String origin, List<String> holders) {}

    /** A transmission attempt during the most recent round; kind is SENT or LOST. */
    public record Transfer(int round, String from, String to, String packetId, String kind) {}

    public record Counters(int devicesTotal, int devicesActive, int bridgesTotal, int bridgesOnline, int awaitingDelivery,
                           long forwarded, long lost, long suppressed) {}
}
