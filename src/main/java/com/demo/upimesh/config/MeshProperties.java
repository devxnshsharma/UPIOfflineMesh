package com.demo.upimesh.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.List;

/** Every tunable of the prototype lives here (bound from {@code upi.mesh.*}). */
@ConfigurationProperties(prefix = "upi.mesh")
public record MeshProperties(
        @DefaultValue("86400") long freshnessWindowSeconds,
        @DefaultValue("300") long clockSkewSeconds,
        @DefaultValue("5") int defaultTtl,
        @DefaultValue("1000000000") BigDecimal maxAmount,
        @DefaultValue("86400") long idempotencyTtlSeconds,
        @DefaultValue("3") int settlementRetries,
        @DefaultValue("120") long processingTimeoutSeconds,
        @DefaultValue("500") int maxPackets,
        @DefaultValue List<AccountSeed> accounts,
        @DefaultValue Sim sim,
        @DefaultValue Experiments experiments,
        @DefaultValue Security security) {

    public record AccountSeed(String vpa, String name, BigDecimal balance) {}

    /** Work budget of one experiment request (each simulation costs roughly 10 ms of real encryption, mesh and database work). */
    public record Experiments(
            @DefaultValue("600") int maxSimulations,
            @DefaultValue("6000") int maxSubmissions) {}

    /**
     * Policy for a deployment that other people can reach. All off by default so local use stays frictionless.
     *
     * @param hosted           turns the hosted policy on: admin actions need the token (or are disabled when it is blank)
     * @param adminToken       bearer token for reset, simulation settings and topology changes; supplied by an environment variable
     * @param writesPerMinute  per client address limit for POST/PUT/PATCH under /api (0 = unlimited)
     * @param readsPerMinute   per client address limit for GET under /api (0 = unlimited)
     * @param maxBodyBytes     largest accepted request body
     */
    public record Security(
            @DefaultValue("false") boolean hosted,
            @DefaultValue("") String adminToken,
            @DefaultValue("0") int writesPerMinute,
            @DefaultValue("0") int readsPerMinute,
            @DefaultValue("65536") long maxBodyBytes) {}

    public record Sim(
            @DefaultValue("0") double lossProbability,
            @DefaultValue("0") int delayRounds,
            @DefaultValue("86400") long packetExpirySeconds,
            @DefaultValue("42") long seed) {}
}
