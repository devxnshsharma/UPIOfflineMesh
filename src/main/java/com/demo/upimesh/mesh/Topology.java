package com.demo.upimesh.mesh;

import java.util.*;

/**
 * Who can hear whom. Devices are only "in range" of their direct neighbours, so a packet needs
 * several gossip rounds (hops) to cross the network. {@link #fullMesh()} reproduces the simplistic
 * "everyone hears everyone" broadcast model for comparison.
 */
public final class Topology {

    public record DeviceDef(String id, String label, Role role, int x, int y) {}
    public record Link(String a, String b) {}

    private final List<DeviceDef> devices;
    private final Map<String, Set<String>> adjacency;

    private Topology(List<DeviceDef> devices, Map<String, Set<String>> adjacency) {
        this.devices = List.copyOf(devices);
        this.adjacency = adjacency;
    }

    public List<DeviceDef> devices() { return devices; }

    public Set<String> neighbors(String id) {
        return Collections.unmodifiableSet(adjacency.getOrDefault(id, Set.of()));
    }

    public List<Link> links() {
        List<Link> out = new ArrayList<>();
        for (var e : new TreeMap<>(adjacency).entrySet()) {
            for (String other : e.getValue()) {
                if (e.getKey().compareTo(other) < 0) out.add(new Link(e.getKey(), other));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ builder

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<DeviceDef> devices = new ArrayList<>();
        private final Map<String, Set<String>> adjacency = new HashMap<>();

        public Builder relay(String id) { return device(id, id, Role.RELAY, 0, 0); }
        public Builder bridge(String id) { return device(id, id, Role.BRIDGE, 0, 0); }
        public Builder relay(String id, String label, int x, int y) { return device(id, label, Role.RELAY, x, y); }
        public Builder bridge(String id, String label, int x, int y) { return device(id, label, Role.BRIDGE, x, y); }

        private Builder device(String id, String label, Role role, int x, int y) {
            if (x == 0 && y == 0) { x = 70 + devices.size() * 110; y = 260; } // simple auto layout
            devices.add(new DeviceDef(id, label, role, x, y));
            adjacency.put(id, new TreeSet<>());
            return this;
        }

        public Builder link(String a, String b) {
            if (!adjacency.containsKey(a) || !adjacency.containsKey(b)) throw new IllegalArgumentException("Unknown device in link " + a + "-" + b);
            adjacency.get(a).add(b);
            adjacency.get(b).add(a);
            return this;
        }

        public Topology build() { return new Topology(devices, adjacency); }
    }

    // ------------------------------------------------------------------ presets

    /**
     * Default demo network (hops from the sender in brackets):
     * <pre>
     *   phone-alice - relay-1 - relay-2 - bridge-1          (3)
     *                        \\- relay-3 - bridge-3         (3)
     *                                  \\- relay-4 - bridge-2 (4)
     * </pre>
     */
    public static Topology demo() {
        return demoDevices().link("phone-alice", "relay-1").link("relay-1", "relay-2").link("relay-1", "relay-3")
                .link("relay-2", "bridge-1").link("relay-3", "bridge-3").link("relay-3", "relay-4")
                .link("relay-4", "bridge-2").build();
    }

    /** Same eight devices, but every device hears every other one (global broadcast). */
    public static Topology fullMesh() {
        Builder b = demoDevices();
        List<String> ids = new ArrayList<>(b.adjacency.keySet());
        for (int i = 0; i < ids.size(); i++) for (int j = i + 1; j < ids.size(); j++) b.link(ids.get(i), ids.get(j));
        return b.build();
    }

    /** A single path: the sender is five hops from the only bridge. */
    public static Topology line() {
        return builder()
                .relay("phone-alice", "Alice", 70, 260).relay("relay-1", "relay-1", 245, 260)
                .relay("relay-2", "relay-2", 420, 260).relay("relay-3", "relay-3", 595, 260)
                .relay("relay-4", "relay-4", 770, 260).bridge("bridge-1", "bridge-1", 770, 120)
                .link("phone-alice", "relay-1").link("relay-1", "relay-2").link("relay-2", "relay-3")
                .link("relay-3", "relay-4").link("relay-4", "bridge-1").build();
    }

    public static Topology preset(String name) {
        return switch (name) {
            case "demo" -> demo();
            case "line" -> line();
            case "full-mesh" -> fullMesh();
            default -> throw new IllegalArgumentException("Unknown topology '" + name + "' (demo, line, full-mesh)");
        };
    }

    private static Builder demoDevices() {
        return builder()
                .relay("phone-alice", "Alice", 70, 260).relay("relay-1", "relay-1", 235, 260)
                .relay("relay-2", "relay-2", 400, 130).relay("relay-3", "relay-3", 400, 390)
                .relay("relay-4", "relay-4", 570, 440)
                .bridge("bridge-1", "bridge-1", 590, 130).bridge("bridge-3", "bridge-3", 590, 285)
                .bridge("bridge-2", "bridge-2", 760, 440);
    }
}
