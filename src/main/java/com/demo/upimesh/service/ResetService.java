package com.demo.upimesh.service;

import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.model.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Restores the reproducible baseline: configured accounts and balances, empty ledger, no claims.
 * Runs on POST /api/mesh/reset, and at startup only when the database is empty (so the default
 * in-memory H2 always starts from the baseline, while a file-backed H2 keeps its data and claims).
 */
@Service
public class ResetService {

    private static final Logger log = LoggerFactory.getLogger(ResetService.class);

    public static final String SIM_SENDER = "sim-sender" + Account.SIM_SUFFIX;
    public static final String SIM_RECEIVER = "sim-receiver" + Account.SIM_SUFFIX;
    private static final BigDecimal SIM_BALANCE = new BigDecimal("1000000000000.00");

    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final PacketRecordRepository packets;
    private final IngestEventRepository events;
    private final PacketClaimService claims;
    private final MeshSimulatorService mesh;
    private final DemoService demo;
    private final MeshProperties props;

    public ResetService(AccountRepository accounts, LedgerEntryRepository ledger, PacketRecordRepository packets,
                        IngestEventRepository events, PacketClaimService claims, MeshSimulatorService mesh,
                        DemoService demo, MeshProperties props) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.packets = packets;
        this.events = events;
        this.claims = claims;
        this.mesh = mesh;
        this.demo = demo;
        this.props = props;
    }

    @PostConstruct
    void seedOnStartup() {
        if (accounts.count() == 0) reset();
    }

    /** Full baseline: database, idempotency state, mesh (demo topology) and the sender's outbox. */
    public void reset() {
        reset(null);
    }

    @Transactional
    public void reset(String topology) {
        // Child tables first: ledger rows reference accounts.
        events.deleteAllInBatch();
        ledger.deleteAllInBatch();
        packets.deleteAllInBatch();
        accounts.deleteAllInBatch();
        for (MeshProperties.AccountSeed s : props.accounts()) {
            accounts.save(new Account(s.vpa(), s.name(), s.balance()));
        }
        accounts.save(new Account(SIM_SENDER, "Experiment sender", SIM_BALANCE));
        accounts.save(new Account(SIM_RECEIVER, "Experiment receiver", SIM_BALANCE));
        claims.clear();
        mesh.reset(topology);
        demo.reset();
        log.info("Backend reset to baseline ({} demo accounts)", props.accounts().size());
    }
}
