package com.demo.upimesh;

import com.demo.upimesh.dto.IngestResult;
import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.ResetService;
import com.demo.upimesh.service.SettlementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** A settlement that throws after the packet was claimed must never leave it stuck or half-applied. */
@SpringBootTest
@Import({TestPayments.class, RetryableFailureTest.FlakyConfig.class})
class RetryableFailureTest {

    static class FlakySettlementService extends SettlementService {
        private final AtomicInteger transientFailures = new AtomicInteger();
        private final AtomicInteger hardFailures = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        // Accessors, not fields: the injected bean is a Spring proxy, so only method calls reach the real object.
        public void failTransiently(int times) { transientFailures.set(times); }
        public void failHard(int times) { hardFailures.set(times); }
        public int calls() { return calls.get(); }
        public void clear() { transientFailures.set(0); hardFailures.set(0); calls.set(0); }

        FlakySettlementService(AccountRepository a, LedgerEntryRepository l, PacketRecordRepository p, MeshProperties props) {
            super(a, l, p, props);
        }

        @Override
        @Transactional
        public SettlementOutcome settle(PaymentInstruction i, String hash, String bridge, int hops) {
            calls.incrementAndGet();
            if (transientFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new ConcurrencyFailureException("simulated lock conflict");
            }
            if (hardFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("simulated crash");
            }
            return super.settle(i, hash, bridge, hops);
        }
    }

    @TestConfiguration
    static class FlakyConfig {
        @Bean @Primary
        FlakySettlementService flakySettlement(AccountRepository a, LedgerEntryRepository l,
                                               PacketRecordRepository p, MeshProperties props) {
            return new FlakySettlementService(a, l, p, props);
        }
    }

    @Autowired private BridgeIngestionService bridge;
    @Autowired private FlakySettlementService flaky;
    @Autowired private ResetService reset;
    @Autowired private TestPayments pay;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private PacketRecordRepository packets;

    @BeforeEach
    void baseline() {
        reset.reset();
        flaky.clear();
    }

    @Test
    void transientConflictsAreRetriedInsideTheBackend() {
        flaky.failTransiently(2); // fails twice, 3rd attempt succeeds (settlement-retries=3)
        IngestResult r = bridge.ingest(pay.packet("alice@demo", "bob@demo", "10"), "bridge-1", 1);

        assertEquals(Outcome.SETTLED, r.outcome());
        assertEquals(3, flaky.calls());
        assertEquals(1, ledger.count());
    }

    @Test
    void persistentConflictBecomesRetryableFailureAndReleasesTheClaim() {
        flaky.failTransiently(99);
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "10");

        IngestResult r = bridge.ingest(p, "bridge-1", 1);

        assertEquals(Outcome.FAILED, r.outcome());
        assertEquals(Reason.CONCURRENCY_CONFLICT, r.reason());
        assertTrue(r.retryable());
        assertEquals(0, packets.count(), "no PROCESSING row left behind");
        assertEquals(new BigDecimal("5000.00"), accounts.findById("alice@demo").orElseThrow().getBalance());

        flaky.failTransiently(0); // the network heals; the bridge re-uploads the same bytes
        IngestResult retry = bridge.ingest(p, "bridge-1", 1);
        assertEquals(Outcome.SETTLED, retry.outcome());
        assertEquals(1, ledger.count());
    }

    @Test
    void unexpectedExceptionIsContainedReleasedAndRetriable() {
        flaky.failHard(1);
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "10");

        IngestResult r = bridge.ingest(p, "bridge-1", 1);
        assertEquals(Outcome.FAILED, r.outcome());
        assertEquals(Reason.PROCESSING_ERROR, r.reason());
        assertEquals(0, packets.countByState(PacketRecord.State.PROCESSING));
        assertEquals(0, ledger.count());

        assertEquals(Outcome.SETTLED, bridge.ingest(p, "bridge-1", 1).outcome());
        assertEquals(Outcome.DUPLICATE_DROPPED, bridge.ingest(p, "bridge-2", 1).outcome());
        assertEquals(1, ledger.count());
    }
}
