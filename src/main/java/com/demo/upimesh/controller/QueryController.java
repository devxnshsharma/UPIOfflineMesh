package com.demo.upimesh.controller;

import com.demo.upimesh.config.MeshProperties;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.dto.*;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.IngestEventRepository;
import com.demo.upimesh.service.MeshSimulatorService;
import com.demo.upimesh.service.StatsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only views for the dashboard. */
@RestController
@RequestMapping("/api")
public class QueryController {

    private final ServerKeyHolder serverKey;
    private final AccountRepository accounts;
    private final IngestEventRepository events;
    private final StatsService stats;
    private final MeshSimulatorService mesh;
    private final MeshProperties.Security security;
    private final boolean h2Console;

    public QueryController(ServerKeyHolder serverKey, AccountRepository accounts, IngestEventRepository events,
                           StatsService stats, MeshSimulatorService mesh, MeshProperties props,
                           @Value("${spring.h2.console.enabled:false}") boolean h2Console) {
        this.serverKey = serverKey;
        this.accounts = accounts;
        this.events = events;
        this.stats = stats;
        this.mesh = mesh;
        this.security = props.security();
        this.h2Console = h2Console;
    }

    @GetMapping("/health")
    public HealthView health() {
        String database;
        try {
            accounts.count();
            database = "UP";
        } catch (RuntimeException e) {
            database = "DOWN";
        }
        return new HealthView("UP".equals(database) ? "UP" : "DEGRADED", database, mesh.snapshot().devices().size(),
                java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                security.hosted() ? "hosted" : "local", security.hosted(), h2Console && !security.hosted());
    }

    @GetMapping("/server-key")
    public ServerKeyView serverKey() {
        return new ServerKeyView(serverKey.getPublicKeyBase64(), serverKey.publicKeyFingerprint(),
                "RSA-2048 / OAEP-SHA256", "RSA-OAEP wraps a fresh AES-256-GCM key for every packet");
    }

    @GetMapping("/accounts")
    public List<AccountView> accounts() {
        return accounts.findByVpaNotLikeOrderByVpa("%" + Account.SIM_SUFFIX).stream().map(AccountView::of).toList();
    }

    @GetMapping("/transactions")
    public List<TransactionView> transactions() {
        return events.findTop50BySimulationFalseOrderByIdDesc().stream().map(TransactionView::of).toList();
    }

    @GetMapping("/stats")
    public StatsView stats() {
        return stats.stats();
    }
}
