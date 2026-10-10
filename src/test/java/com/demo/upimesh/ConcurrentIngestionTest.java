package com.demo.upimesh;

import com.demo.upimesh.dto.IngestResult;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.ResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Many threads, shared accounts, duplicate storms: the ledger must stay exactly consistent. */
@SpringBootTest
@Import(TestPayments.class)
class ConcurrentIngestionTest {

    @Autowired private BridgeIngestionService bridge;
    @Autowired private ResetService reset;
    @Autowired private TestPayments pay;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private PacketRecordRepository packets;

    @BeforeEach
    void baseline() {
        reset.reset();
    }

    /** Release all tasks at the same instant and collect their results. */
    private List<IngestResult> fireTogether(List<Callable<IngestResult>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, tasks.size()));
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<IngestResult>> futures = new ArrayList<>();
        for (Callable<IngestResult> t : tasks) {
            futures.add(pool.submit(() -> { gate.await(); return t.call(); }));
        }
        gate.countDown();
        List<IngestResult> out = new ArrayList<>();
        for (Future<IngestResult> f : futures) out.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        return out;
    }

    private long count(List<IngestResult> rs, Outcome o) {
        return rs.stream().filter(r -> r.outcome() == o).count();
    }

    private BigDecimal total() {
        return accounts.findAll().stream().map(Account::getBalance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void threeBridgesDeliveringTheSamePacketSimultaneouslySettleExactlyOnce() throws Exception {
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "100.00");
        List<Callable<IngestResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String node = "bridge-" + i;
            tasks.add(() -> bridge.ingest(p, node, 3));
        }

        List<IngestResult> results = fireTogether(tasks);

        assertEquals(1, count(results, Outcome.SETTLED));
        assertEquals(2, count(results, Outcome.DUPLICATE_DROPPED));
        assertEquals(new BigDecimal("4900.00"), accounts.findById("alice@demo").orElseThrow().getBalance());
        assertEquals(new BigDecimal("1100.00"), accounts.findById("bob@demo").orElseThrow().getBalance());
        assertEquals(1, ledger.count());
        assertEquals(1, packets.count());
        assertEquals(0, packets.countByState(PacketRecord.State.PROCESSING));
    }

    @Test
    void thirtyConcurrentCopiesOfOnePacketStillSettleOnce() throws Exception {
        MeshPacket p = pay.packet("carol@demo", "dave@demo", "40.00");
        List<Callable<IngestResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String node = "bridge-" + i;
            tasks.add(() -> bridge.ingest(p, node, 2));
        }
        List<IngestResult> results = fireTogether(tasks);
        assertEquals(1, count(results, Outcome.SETTLED));
        assertEquals(29, count(results, Outcome.DUPLICATE_DROPPED));
        assertEquals(1, ledger.count());
    }

    @Test
    void sameLogicalPaymentUnderDifferentCiphertextsRacesToOneSettlement() throws Exception {
        PaymentInstruction ins = pay.instruction("alice@demo", "bob@demo", "75.00");
        List<Callable<IngestResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            MeshPacket repackaged = pay.wrap(ins);
            tasks.add(() -> bridge.ingest(repackaged, "bridge-x", 1));
        }
        List<IngestResult> results = fireTogether(tasks);

        assertEquals(1, count(results, Outcome.SETTLED));
        assertEquals(5, results.stream()
                .filter(r -> r.outcome() == Outcome.REJECTED && r.reason() == Reason.NONCE_REUSED).count());
        assertEquals(1, ledger.count());
        assertEquals(new BigDecimal("4925.00"), accounts.findById("alice@demo").orElseThrow().getBalance());
    }

    @Test
    void manyDistinctPaymentsOnSharedAccountsKeepBalancesExactAndConserveMoney() throws Exception {
        BigDecimal before = total();
        String[][] flows = {
                {"alice@demo", "bob@demo", "10.00"}, {"bob@demo", "carol@demo", "20.00"},
                {"carol@demo", "alice@demo", "5.50"}, {"alice@demo", "dave@demo", "7.25"},
                {"dave@demo", "bob@demo", "3.00"}, {"bob@demo", "alice@demo", "1.75"},
        };
        int perFlow = 5;
        List<Callable<IngestResult>> tasks = new ArrayList<>();
        BigDecimal aliceNet = BigDecimal.ZERO, bobNet = BigDecimal.ZERO,
                carolNet = BigDecimal.ZERO, daveNet = BigDecimal.ZERO;
        for (String[] f : flows) {
            BigDecimal amt = new BigDecimal(f[2]).multiply(BigDecimal.valueOf(perFlow));
            for (int i = 0; i < perFlow; i++) {
                MeshPacket p = pay.packet(f[0], f[1], f[2]);
                // every packet is delivered by three bridges at once
                for (int b = 0; b < 3; b++) {
                    String node = "bridge-" + b;
                    tasks.add(() -> bridge.ingest(p, node, 2));
                }
            }
            switch (f[0]) { case "alice@demo" -> aliceNet = aliceNet.subtract(amt); case "bob@demo" -> bobNet = bobNet.subtract(amt);
                case "carol@demo" -> carolNet = carolNet.subtract(amt); default -> daveNet = daveNet.subtract(amt); }
            switch (f[1]) { case "alice@demo" -> aliceNet = aliceNet.add(amt); case "bob@demo" -> bobNet = bobNet.add(amt);
                case "carol@demo" -> carolNet = carolNet.add(amt); default -> daveNet = daveNet.add(amt); }
        }

        List<IngestResult> results = fireTogether(tasks);

        int payments = flows.length * perFlow;
        assertEquals(payments, count(results, Outcome.SETTLED), "each logical payment settled once");
        assertEquals(payments * 2L, count(results, Outcome.DUPLICATE_DROPPED));
        assertEquals(0, count(results, Outcome.FAILED), "lock contention must not surface as failure");
        assertEquals(payments, ledger.count());
        assertEquals(new BigDecimal("5000.00").add(aliceNet), accounts.findById("alice@demo").orElseThrow().getBalance());
        assertEquals(new BigDecimal("1000.00").add(bobNet), accounts.findById("bob@demo").orElseThrow().getBalance());
        assertEquals(new BigDecimal("2500.00").add(carolNet), accounts.findById("carol@demo").orElseThrow().getBalance());
        assertEquals(new BigDecimal("500.00").add(daveNet), accounts.findById("dave@demo").orElseThrow().getBalance());
        assertEquals(0, before.compareTo(total()), "money is conserved");
        assertEquals(0, packets.countByState(PacketRecord.State.PROCESSING));
    }

    @Test
    void concurrentSpendingNeverOverdrawsAnAccount() throws Exception {
        // Dave holds 500. Ten concurrent payments of 100: exactly five can succeed.
        List<Callable<IngestResult>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MeshPacket p = pay.packet("dave@demo", "bob@demo", "100.00");
            tasks.add(() -> bridge.ingest(p, "bridge-1", 1));
        }
        List<IngestResult> results = fireTogether(tasks);

        assertEquals(5, count(results, Outcome.SETTLED));
        assertEquals(5, results.stream()
                .filter(r -> r.outcome() == Outcome.REJECTED && r.reason() == Reason.INSUFFICIENT_FUNDS).count());
        assertEquals(new BigDecimal("0.00"), accounts.findById("dave@demo").orElseThrow().getBalance());
        assertEquals(new BigDecimal("1500.00"), accounts.findById("bob@demo").orElseThrow().getBalance());
        assertEquals(5, ledger.count());
    }
}
