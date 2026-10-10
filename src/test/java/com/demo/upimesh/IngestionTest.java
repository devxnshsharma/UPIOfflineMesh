package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.dto.IngestResult;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.PacketClaimService;
import com.demo.upimesh.service.ResetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/** The backend pipeline end to end, asserting on real database state, not just return values. */
@SpringBootTest
@Import(TestPayments.class)
class IngestionTest {

    @Autowired private BridgeIngestionService bridge;
    @Autowired private ResetService reset;
    @Autowired private PacketClaimService claims;
    @Autowired private TestPayments pay;
    @Autowired private HybridCryptoService crypto;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private PacketRecordRepository packets;
    @Autowired private IngestEventRepository events;

    @BeforeEach
    void baseline() {
        reset.reset();
    }

    private BigDecimal balance(String vpa) {
        return accounts.findById(vpa).orElseThrow().getBalance();
    }

    private void assertBalances(String alice, String bob) {
        assertEquals(new BigDecimal(alice), balance("alice@demo"));
        assertEquals(new BigDecimal(bob), balance("bob@demo"));
    }

    private IngestResult send(MeshPacket p) {
        return bridge.ingest(p, "bridge-1", 3);
    }

    // ------------------------------------------------------------ happy path

    @Test
    void validPaymentDebitsCreditsAndWritesExactlyOneLedgerEntry() {
        IngestResult r = send(pay.packet("alice@demo", "bob@demo", "250.50"));

        assertEquals(Outcome.SETTLED, r.outcome());
        assertNotNull(r.ledgerEntryId());
        assertBalances("4749.50", "1250.50");
        assertEquals(1, ledger.count());
        LedgerEntry e = ledger.findAll().get(0);
        assertEquals("alice@demo", e.getSender().getVpa());
        assertEquals(0, new BigDecimal("250.50").compareTo(e.getAmount()));
        assertEquals("bridge-1", e.getBridgeNodeId());
        assertEquals(3, e.getHopCount());
        PacketRecord rec = packets.findByPacketHash(r.packetHash()).orElseThrow();
        assertEquals(PacketRecord.State.SETTLED, rec.getState());
        assertEquals(e.getId(), rec.getLedgerEntryId());
    }

    // ------------------------------------------------- business-rule rejections

    @Test
    void insufficientFundsIsRejectedWithoutTouchingBalancesOrLedger() {
        IngestResult r = send(pay.packet("dave@demo", "bob@demo", "500.01"));

        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(Reason.INSUFFICIENT_FUNDS, r.reason());
        assertEquals(new BigDecimal("500.00"), balance("dave@demo"));
        assertEquals(new BigDecimal("1000.00"), balance("bob@demo"));
        assertEquals(0, ledger.count());
        assertEquals(PacketRecord.State.REJECTED, packets.findByPacketHash(r.packetHash()).orElseThrow().getState());
    }

    @Test
    void exactBalanceCanBeSpent() {
        assertEquals(Outcome.SETTLED, send(pay.packet("dave@demo", "bob@demo", "500.00")).outcome());
        assertEquals(new BigDecimal("0.00"), balance("dave@demo"));
    }

    @Test
    void invalidAmountsAreRejected() {
        for (String bad : new String[]{"0", "-5.00", "10.001", "1000000000.01"}) {
            IngestResult r = send(pay.packet("alice@demo", "bob@demo", bad));
            assertEquals(Outcome.REJECTED, r.outcome(), bad);
            assertEquals(Reason.INVALID_AMOUNT, r.reason(), bad);
        }
        assertBalances("5000.00", "1000.00");
        assertEquals(0, ledger.count());
    }

    @Test
    void unknownAccountsAreRejected() {
        assertEquals(Reason.UNKNOWN_SENDER,
                send(pay.packet("mallory@demo", "bob@demo", "10")).reason());
        assertEquals(Reason.UNKNOWN_RECEIVER,
                send(pay.packet("alice@demo", "nobody@demo", "10")).reason());
        assertBalances("5000.00", "1000.00");
        assertEquals(0, ledger.count());
    }

    @Test
    void payingYourselfIsRejected() {
        IngestResult r = send(pay.packet("alice@demo", "alice@demo", "10"));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(Reason.SAME_ACCOUNT, r.reason());
        assertBalances("5000.00", "1000.00");
    }

    @Test
    void incompletePayloadIsRejectedNotCrashed() {
        PaymentInstruction missingNonce = new PaymentInstruction("alice@demo", "bob@demo",
                new BigDecimal("5"), null, System.currentTimeMillis());
        IngestResult r = send(pay.wrap(missingNonce));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(Reason.MALFORMED_PAYLOAD, r.reason());
        assertEquals(0, ledger.count());
    }

    // --------------------------------------------------------------- freshness

    @Test
    void staleTimestampIsRejectedBeforeSettlement() {
        long old = System.currentTimeMillis() - Duration.ofHours(25).toMillis();
        IngestResult r = send(pay.wrap(pay.instruction("alice@demo", "bob@demo", "10", old)));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(Reason.STALE_TIMESTAMP, r.reason());
        assertBalances("5000.00", "1000.00");
        assertEquals(0, ledger.count());
    }

    @Test
    void justInsideTheWindowIsAccepted() {
        long recent = System.currentTimeMillis() - Duration.ofHours(23).toMillis();
        assertEquals(Outcome.SETTLED,
                send(pay.wrap(pay.instruction("alice@demo", "bob@demo", "10", recent))).outcome());
    }

    @Test
    void farFutureTimestampIsRejectedButSmallClockSkewIsTolerated() {
        long now = System.currentTimeMillis();
        IngestResult future = send(pay.wrap(pay.instruction("alice@demo", "bob@demo", "10", now + Duration.ofMinutes(30).toMillis())));
        assertEquals(Reason.FUTURE_TIMESTAMP, future.reason());

        IngestResult skewed = send(pay.wrap(pay.instruction("alice@demo", "bob@demo", "10", now + Duration.ofMinutes(2).toMillis())));
        assertEquals(Outcome.SETTLED, skewed.outcome());
    }

    // ------------------------------------------------- the four duplicate cases

    @Test
    void case1_byteIdenticalRedeliveryIsDroppedAsDuplicate() {
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "100");
        IngestResult first = send(p);
        IngestResult second = send(p);
        IngestResult third = bridge.ingest(p, "bridge-2", 4);

        assertEquals(Outcome.SETTLED, first.outcome());
        assertEquals(Outcome.DUPLICATE_DROPPED, second.outcome());
        assertEquals(Outcome.DUPLICATE_DROPPED, third.outcome());
        assertEquals(first.packetHash(), second.packetHash());
        assertBalances("4900.00", "1100.00");
        assertEquals(1, ledger.count());
        assertEquals(1, packets.count());
        assertEquals(1, events.countByOutcomeAndSimulationFalse(Outcome.SETTLED));
        assertEquals(2, events.countByOutcomeAndSimulationFalse(Outcome.DUPLICATE_DROPPED));
    }

    @Test
    void databaseConstraintStillCatchesDuplicatesWhenTheInMemoryFastPathIsEmpty() {
        // Models a restart / a second backend instance: the in-memory map knows nothing, the database does.
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "100");
        assertEquals(Outcome.SETTLED, send(p).outcome());

        claims.clear();

        assertEquals(Outcome.DUPLICATE_DROPPED, send(p).outcome());
        assertBalances("4900.00", "1100.00");
        assertEquals(1, ledger.count());
        assertEquals(1, packets.count());
    }

    @Test
    void case2_sameLogicalPaymentRepackagedUnderNewCiphertextCannotSettleTwice() {
        PaymentInstruction ins = pay.instruction("alice@demo", "bob@demo", "100");
        MeshPacket original = pay.wrap(ins);
        MeshPacket repackaged = pay.wrap(ins); // same nonce, fresh AES key + IV => different ciphertext

        assertNotEquals(original.ciphertext(), repackaged.ciphertext());
        assertEquals(Outcome.SETTLED, send(original).outcome());

        IngestResult r = send(repackaged);
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(Reason.NONCE_REUSED, r.reason());
        assertBalances("4900.00", "1100.00");
        assertEquals(1, ledger.count());
    }

    @Test
    void case3_twoLegitimatePaymentsWithSameSenderReceiverAmountBothSettle() {
        assertEquals(Outcome.SETTLED, send(pay.packet("alice@demo", "bob@demo", "100")).outcome());
        assertEquals(Outcome.SETTLED, send(pay.packet("alice@demo", "bob@demo", "100")).outcome());
        assertBalances("4800.00", "1200.00");
        assertEquals(2, ledger.count());
    }

    @Test
    void case4_stalePacketIsRejectedEvenIfNeverSeenBefore() {
        long old = System.currentTimeMillis() - Duration.ofDays(3).toMillis();
        assertEquals(Reason.STALE_TIMESTAMP,
                send(pay.wrap(pay.instruction("alice@demo", "bob@demo", "10", old))).reason());
    }

    @Test
    void repackagingARejectedInstructionRetriesItWhenFundsArrive() {
        // Insufficient funds does not consume the nonce: no money moved, so a later copy may succeed.
        PaymentInstruction ins = pay.instruction("dave@demo", "bob@demo", "600");
        assertEquals(Reason.INSUFFICIENT_FUNDS, send(pay.wrap(ins)).reason());
        assertEquals(Outcome.SETTLED, send(pay.packet("alice@demo", "dave@demo", "200")).outcome());
        assertEquals(Outcome.SETTLED, send(pay.wrap(ins)).outcome());
        assertEquals(new BigDecimal("100.00"), balance("dave@demo"));
    }

    // ------------------------------------------------------------ tamper detection

    @Test
    void tamperedCiphertextFailsAuthenticationAndChangesNothing() {
        MeshPacket good = pay.packet("alice@demo", "bob@demo", "100");
        MeshPacket tampered = new MeshPacket(good.packetId(), good.ttl(), good.createdAt(),
                crypto.flipOneByte(good.ciphertext()));

        IngestResult r = send(tampered);

        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(Reason.DECRYPTION_FAILED, r.reason());
        assertBalances("5000.00", "1000.00");
        assertEquals(0, ledger.count());
        // The untouched original still settles: tampering burned only the altered copy's fingerprint.
        assertEquals(Outcome.SETTLED, send(good).outcome());
        // And re-delivering the tampered bytes is a plain duplicate.
        assertEquals(Outcome.DUPLICATE_DROPPED, send(tampered).outcome());
    }

    @Test
    void junkCiphertextIsRejectedAsDecryptionFailure() {
        IngestResult r = send(new MeshPacket("p1", 3, System.currentTimeMillis(), "bm90LWEtcGFja2V0"));
        assertEquals(Outcome.REJECTED, r.outcome());
        assertEquals(Reason.DECRYPTION_FAILED, r.reason());
    }

    @Test
    void structurallyInvalidPacketIsRejectedAsMalformed() {
        assertEquals(Reason.MALFORMED_PACKET,
                send(new MeshPacket("p1", 3, System.currentTimeMillis(), "")).reason());
        assertEquals(Reason.MALFORMED_PACKET,
                send(new MeshPacket("", 3, System.currentTimeMillis(), "abc")).reason());
        assertEquals(Reason.MALFORMED_PACKET, send(null).reason());
        assertEquals(0, packets.count(), "malformed requests claim nothing");
    }

    // ---------------------------------------------------- atomicity & recovery

    @Test
    void failureAfterDebitRollsBackBalancesAndReleasesTheClaim() {
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "100");
        String hash = crypto.sha256Hex(p.ciphertext());
        // Sabotage: a ledger row already owns this packet fingerprint, so the ledger INSERT inside
        // the settlement transaction (after both balances were changed in memory) violates a
        // unique constraint at flush time.
        ledger.saveAndFlush(new LedgerEntry("other-nonce", hash,
                accounts.getReferenceById("carol@demo"), accounts.getReferenceById("dave@demo"),
                new BigDecimal("1.00"), java.time.Instant.now(), "x", 0));
        BigDecimal carolBefore = balance("carol@demo"), daveBefore = balance("dave@demo");

        IngestResult r = send(p);

        assertEquals(Outcome.FAILED, r.outcome());
        assertTrue(r.retryable());
        assertBalances("5000.00", "1000.00");            // no partial debit or credit
        assertEquals(carolBefore, balance("carol@demo"));
        assertEquals(daveBefore, balance("dave@demo"));
        assertEquals(1, ledger.count());                 // only the sabotage row
        assertTrue(packets.findByPacketHash(hash).isEmpty(), "claim released, packet not stuck in PROCESSING");
        assertEquals(0, packets.countByState(PacketRecord.State.PROCESSING));
    }

    // ------------------------------------------------------------------- reset

    @Test
    void resetRestoresBaselineAndForgetsPackets() {
        MeshPacket p = pay.packet("alice@demo", "bob@demo", "100");
        assertEquals(Outcome.SETTLED, send(p).outcome());

        reset.reset();

        assertBalances("5000.00", "1000.00");
        assertEquals(0, ledger.count());
        assertEquals(0, packets.count());
        assertEquals(0, events.count());
        assertEquals(Outcome.SETTLED, send(p).outcome(), "same packet is new again after a full reset");
    }
}
