package com.demo.upimesh.service;

import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.dto.*;
import com.demo.upimesh.dto.ExperimentRequest.Type;
import com.demo.upimesh.dto.ExperimentResult.MetricInfo;
import com.demo.upimesh.dto.ExperimentResult.Row;
import com.demo.upimesh.mesh.*;
import com.demo.upimesh.model.IngestEvent;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.model.PaymentInstruction;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleFunction;

/**
 * Parameter sweeps over the real mesh simulator and the real backend pipeline.
 *
 * Each run builds a private {@link MeshNetwork} (never the live demo one), encrypts a real payment
 * between two hidden "@sim" accounts, gossips and uploads through {@link BridgeIngestionService}.
 * Bridge ids carry the "sim-" prefix so these deliveries stay out of the demo ledger and stats.
 *
 * Time is measured in gossip rounds. Everything is seeded: same request, same seed, same numbers.
 */
@Service
public class ExperimentService {

    private static final List<MetricInfo> NET_METRICS = List.of(
            new MetricInfo("deliveryRatePct", "Delivery success rate", "%"),
            new MetricInfo("avgSettlementDelayRounds", "Avg settlement delay", "rounds"),
            new MetricInfo("maxSettlementDelayRounds", "Max settlement delay", "rounds"),
            new MetricInfo("avgTimeToFirstUploadRounds", "Avg time to first upload", "rounds"),
            new MetricInfo("avgReachabilityPct", "Devices holding a copy", "%"),
            new MetricInfo("avgCopies", "Copies in the mesh", "devices"),
            new MetricInfo("avgTransmissions", "Transmissions sent", "count"),
            new MetricInfo("avgLost", "Transmissions lost", "count"),
            new MetricInfo("avgSuppressed", "Redundant offers suppressed", "count"),
            new MetricInfo("avgDuplicateUploads", "Duplicate uploads dropped by backend", "count"));

    private static final List<MetricInfo> DUPLICATE_METRICS = List.of(
            new MetricInfo("avgSettlements", "Settlements per packet", "count"),
            new MetricInfo("avgDuplicateDrops", "Duplicate drops per packet", "count"),
            new MetricInfo("exactlyOncePct", "Runs with exactly one settlement", "%"));

    private static final int MAX_VALUES = 12;
    private static final int MAX_RUNS = 100;

    private record Def(Type type, String title, String description, String parameterName, String parameterUnit,
                       List<Double> defaults, int defaultRuns, List<MetricInfo> metrics,
                       double min, double max, boolean whole) {}

    private static final List<Def> DEFS = List.of(
            new Def(Type.CONNECTIVITY_DELAY, "Connectivity delay",
                    "All bridges stay offline for N rounds, then get internet. How long does settlement take?",
                    "Bridge online after", "rounds", List.of(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 12.0, 16.0), 20, NET_METRICS, 0, 100, true),
            new Def(Type.PACKET_LOSS, "Packet loss",
                    "Each transmission is lost with probability p (sender keeps the copy and retries). 12 rounds to deliver.",
                    "Loss probability", "p", List.of(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9), 30, NET_METRICS, 0, 1, false),
            new Def(Type.TTL, "TTL (hop budget)",
                    "Packet starts with TTL hops. Bridges are 3 and 4 hops from the sender. No loss, 12 rounds.",
                    "Initial TTL", "hops", List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0), 5, NET_METRICS, 1, 32, true),
            new Def(Type.GOSSIP_ROUNDS, "Gossip replication",
                    "Only N gossip rounds are allowed (20% loss, bridges online). More rounds = more copies and more duplicates.",
                    "Gossip rounds", "rounds", List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0), 20, NET_METRICS, 1, 30, true),
            new Def(Type.BRIDGE_AVAILABILITY, "Bridge availability",
                    "k of the 3 bridges (chosen at random per run) have internet from the start. 25% loss, 15 rounds.",
                    "Online bridges", "bridges", List.of(0.0, 1.0, 2.0, 3.0), 30, NET_METRICS, 0, 3, true),
            new Def(Type.DUPLICATE_DELIVERY, "Duplicate delivery",
                    "The same packet reaches the backend K times at the same instant. Exactly one must settle.",
                    "Simultaneous submissions", "copies", List.of(1.0, 2.0, 3.0, 5.0, 8.0, 12.0), 5, DUPLICATE_METRICS, 1, 50, true));

    private record Setup(Topology topology, int ttl, double loss, int delay, int maxRounds, int onlineFrom, int bridgesOnline) {}

    private record Run(boolean delivered, int settledRound, int firstUploadRound, int copies, int devices,
                       long transmissions, long lost, long suppressed, int duplicateUploads) {}

    private final BridgeIngestionService ingestion;
    private final HybridCryptoService crypto;
    private final ServerKeyHolder serverKey;
    private final Clock clock;
    private final MeshProperties.Experiments budget;
    private final List<ExperimentResult> history = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    /** One experiment at a time: each one keeps a request thread and the database busy for seconds. */
    private final Semaphore gate = new Semaphore(1);

    public ExperimentService(BridgeIngestionService ingestion, HybridCryptoService crypto, ServerKeyHolder serverKey,
                             Clock clock, MeshProperties props) {
        this.ingestion = ingestion;
        this.crypto = crypto;
        this.serverKey = serverKey;
        this.clock = clock;
        this.budget = props.experiments();
    }

    public List<ExperimentInfo> types() {
        return DEFS.stream().map(d -> new ExperimentInfo(d.type(), d.title(), d.description(), d.parameterName(),
                d.parameterUnit(), d.defaults(), d.defaultRuns(), d.metrics(), d.min(), d.max(), d.whole(),
                MAX_VALUES, MAX_RUNS, budget.maxSimulations(), budget.maxSubmissions())).toList();
    }

    public List<ExperimentResult> history() {
        List<ExperimentResult> out = new ArrayList<>(history);
        Collections.reverse(out);
        return out;
    }

    public ExperimentResult run(ExperimentRequest req) {
        Def def = DEFS.stream().filter(d -> d.type() == req.type()).findFirst().orElseThrow();
        List<Double> values = req.values() == null || req.values().isEmpty() ? def.defaults() : List.copyOf(req.values());
        if (values.size() > MAX_VALUES) throw new IllegalArgumentException("At most " + MAX_VALUES + " parameter values per experiment");
        values.forEach(v -> validate(def, v));
        int runs = req.runs() != null ? req.runs() : def.defaultRuns();
        checkBudget(def, values, runs);
        if (!gate.tryAcquire()) {
            throw new IllegalStateException("Another experiment is still running. Wait for it to finish, then run this one.");
        }
        try {
            return execute(def, values, runs, req.seed() != null ? req.seed() : 42L);
        } finally {
            gate.release();
        }
    }

    /** Bounded processing: an experiment's cost is values x runs simulations (duplicate delivery: runs x total copies packets). */
    private void checkBudget(Def def, List<Double> values, int runs) {
        long simulations = (long) values.size() * runs;
        if (simulations > budget.maxSimulations()) {
            throw new IllegalArgumentException("This experiment would run " + simulations + " simulations (" + values.size()
                    + " values x " + runs + " runs). The limit is " + budget.maxSimulations() + ": use fewer values or fewer runs.");
        }
        if (def.type() == Type.DUPLICATE_DELIVERY) {
            long submissions = (long) (runs * values.stream().mapToDouble(Double::doubleValue).sum());
            if (submissions > budget.maxSubmissions()) {
                throw new IllegalArgumentException("This experiment would submit " + submissions + " packets (" + runs
                        + " runs x " + (long) values.stream().mapToDouble(Double::doubleValue).sum() + " copies in total). The limit is "
                        + budget.maxSubmissions() + ": use fewer runs or fewer copies.");
            }
        }
    }

    private ExperimentResult execute(Def def, List<Double> values, int runs, long seed) {
        long started = System.nanoTime();
        List<Row> rows = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            double v = values.get(i);
            long pointSeed = seed + 10_007L * i;
            rows.add(def.type() == Type.DUPLICATE_DELIVERY ? duplicateRow(v, runs) : networkRow(def.type(), v, runs, pointSeed, params));
        }
        params.put("seed", seed);
        params.put("runsPerPoint", runs);
        params.put("values", values);
        params.put("timeUnit", "gossip rounds");
        params.put("backend", "real ingestion pipeline, hidden @sim accounts, bridge ids prefixed 'sim-'");

        ExperimentResult result = new ExperimentResult("exp-" + counter.incrementAndGet(), clock.instant(),
                (System.nanoTime() - started) / 1_000_000, def.type(), def.title(), def.parameterName(), def.parameterUnit(),
                params, def.metrics(), rows);
        history.add(result);
        while (history.size() > 20) history.remove(0);
        return result;
    }

    private static void validate(Def def, double v) {
        boolean ok = v >= def.min() && v <= def.max() && (!def.whole() || v == Math.rint(v));
        if (!ok) {
            throw new IllegalArgumentException("Value " + v + " is out of range for " + def.type() + ": accepts "
                    + (def.whole() ? "whole numbers " : "numbers ") + "from " + format(def.min()) + " to " + format(def.max()));
        }
    }

    private static String format(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------- network sweeps

    private Row networkRow(Type type, double v, int runs, long pointSeed, Map<String, Object> params) {
        Topology topo = Topology.demo();
        Setup s = switch (type) {
            case CONNECTIVITY_DELAY -> new Setup(topo, 6, 0.1, 0, 30, (int) v, -1);
            case PACKET_LOSS -> new Setup(topo, 6, v, 0, 12, 0, -1);
            case TTL -> new Setup(topo, (int) v, 0.0, 0, 12, 0, -1);
            case GOSSIP_ROUNDS -> new Setup(topo, 6, 0.2, 0, (int) v, 0, -1);
            case BRIDGE_AVAILABILITY -> new Setup(topo, 6, 0.25, 0, 15, 0, (int) v);
            default -> throw new IllegalStateException();
        };
        params.put("topology", "demo (3 bridges, 3-4 hops from the sender)");
        params.putIfAbsent("baseTtl", type == Type.TTL ? "swept" : s.ttl());
        params.putIfAbsent("baseLossProbability", type == Type.PACKET_LOSS ? "swept" : s.loss());
        params.putIfAbsent("maxRounds", type == Type.GOSSIP_ROUNDS ? "swept" : s.maxRounds());
        params.putIfAbsent("bridgesOnline", type == Type.BRIDGE_AVAILABILITY ? "swept (random subset per run)" : "all 3");
        params.putIfAbsent("bridgeOnlineFromRound", type == Type.CONNECTIVITY_DELAY ? "swept" : 0);

        List<Run> results = new ArrayList<>();
        for (int j = 0; j < runs; j++) results.add(simulate(s, pointSeed + j));
        return aggregate(v, results);
    }

    private Run simulate(Setup s, long seed) {
        MeshNetwork net = new MeshNetwork(s.topology(), new MeshSettings(s.ttl(), s.loss(), s.delay(), 86_400, seed), clock);
        MeshPacket packet = newPacket(s.ttl());
        net.inject(net.senderDeviceId(), packet, "experiment");

        List<String> bridges = s.topology().devices().stream().filter(d -> d.role() == Role.BRIDGE).map(Topology.DeviceDef::id).sorted().toList();
        List<String> chosen = new ArrayList<>(bridges);
        if (s.bridgesOnline() >= 0) {
            Collections.shuffle(chosen, new Random(seed ^ 0x9E3779B97F4A7C15L));
            chosen = chosen.subList(0, s.bridgesOnline());
        }

        int settledRound = -1, firstUpload = -1, duplicateUploads = 0;
        boolean online = false;
        for (int round = 0; round <= s.maxRounds(); round++) {
            if (!online && round >= s.onlineFrom()) {
                chosen.forEach(b -> net.setOnline(b, true));
                online = true;
            }
            for (PendingUpload up : net.pendingUploads()) {
                var r = ingestion.ingest(up.packet(), IngestEvent.SIM_BRIDGE_PREFIX + up.deviceId(), up.hops());
                net.recordUpload(up, r.outcome(), r.reason(), r.message());
                if (firstUpload < 0) firstUpload = round;
                if (r.outcome() == Outcome.SETTLED && settledRound < 0) settledRound = round;
                if (r.outcome() == Outcome.DUPLICATE_DROPPED) duplicateUploads++;
            }
            if (round < s.maxRounds()) net.gossipRound();
        }
        MeshSnapshot.Counters c = net.snapshot("experiment").counters();
        return new Run(settledRound >= 0, settledRound, firstUpload, net.holders(packet.packetId()).size(),
                c.devicesTotal(), c.forwarded(), c.lost(), c.suppressed(), duplicateUploads);
    }

    private Row aggregate(double param, List<Run> runs) {
        int n = runs.size();
        List<Run> delivered = runs.stream().filter(Run::delivered).toList();
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("deliveryRatePct", round(100.0 * delivered.size() / n));
        m.put("avgSettlementDelayRounds", round(delivered.stream().mapToInt(Run::settledRound).average().orElse(Double.NaN)));
        m.put("maxSettlementDelayRounds", delivered.stream().mapToInt(Run::settledRound).max().stream().asDoubleStream().findFirst().orElse(Double.NaN));
        m.put("avgTimeToFirstUploadRounds", round(runs.stream().filter(r -> r.firstUploadRound() >= 0).mapToInt(Run::firstUploadRound).average().orElse(Double.NaN)));
        m.put("avgReachabilityPct", round(runs.stream().mapToDouble(r -> 100.0 * r.copies() / r.devices()).average().orElse(0)));
        m.put("avgCopies", round(runs.stream().mapToInt(Run::copies).average().orElse(0)));
        m.put("avgTransmissions", round(runs.stream().mapToLong(Run::transmissions).average().orElse(0)));
        m.put("avgLost", round(runs.stream().mapToLong(Run::lost).average().orElse(0)));
        m.put("avgSuppressed", round(runs.stream().mapToLong(Run::suppressed).average().orElse(0)));
        m.put("avgDuplicateUploads", round(runs.stream().mapToInt(Run::duplicateUploads).average().orElse(0)));
        return new Row(param, n, m);
    }

    // ------------------------------------------------------------ duplicate delivery

    private Row duplicateRow(double copies, int runs) {
        int k = (int) copies, exactlyOnce = 0;
        double settledSum = 0, dropSum = 0;
        for (int run = 0; run < runs; run++) {
            MeshPacket packet = newPacket(5);
            List<Callable<com.demo.upimesh.dto.IngestResult>> calls = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                String bridge = IngestEvent.SIM_BRIDGE_PREFIX + "bridge-" + i;
                calls.add(() -> ingestion.ingest(packet, bridge, 3));
            }
            var results = Parallel.all(calls);
            long settled = results.stream().filter(r -> r.outcome() == Outcome.SETTLED).count();
            long drops = results.stream().filter(r -> r.outcome() == Outcome.DUPLICATE_DROPPED).count();
            settledSum += settled;
            dropSum += drops;
            if (settled == 1 && drops == k - 1) exactlyOnce++;
        }
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("avgSettlements", round(settledSum / runs));
        m.put("avgDuplicateDrops", round(dropSum / runs));
        m.put("exactlyOncePct", round(100.0 * exactlyOnce / runs));
        return new Row(copies, runs, m);
    }

    // ------------------------------------------------------------------- helpers

    private MeshPacket newPacket(int ttl) {
        PaymentInstruction ins = new PaymentInstruction(ResetService.SIM_SENDER, ResetService.SIM_RECEIVER,
                new BigDecimal("1.00"), UUID.randomUUID().toString(), clock.millis());
        return new MeshPacket(UUID.randomUUID().toString(), ttl, clock.millis(), crypto.encrypt(ins, serverKey.getPublicKey()));
    }

    private static double round(double v) {
        return Double.isNaN(v) ? v : Math.round(v * 1000.0) / 1000.0;
    }
}
