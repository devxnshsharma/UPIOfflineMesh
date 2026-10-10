package com.demo.upimesh.mesh;

/**
 * Tunable network behaviour. All randomness derives from {@code seed}, so a run is reproducible.
 *
 * @param defaultTtl          hop budget given to new packets
 * @param lossProbability     chance (0..1) that one transmission is lost; the sender keeps its copy and retries next round
 * @param delayRounds         extra rounds a transmission spends in flight before the receiver gets it
 * @param packetExpirySeconds devices stop forwarding/uploading a packet older than this (outer createdAt)
 */
public record MeshSettings(int defaultTtl, double lossProbability, int delayRounds, long packetExpirySeconds, long seed) {

    public MeshSettings {
        if (defaultTtl < 1 || defaultTtl > 32) throw new IllegalArgumentException("defaultTtl must be 1..32");
        if (lossProbability < 0 || lossProbability > 1) throw new IllegalArgumentException("lossProbability must be 0..1");
        if (delayRounds < 0 || delayRounds > 20) throw new IllegalArgumentException("delayRounds must be 0..20");
        if (packetExpirySeconds < 1) throw new IllegalArgumentException("packetExpirySeconds must be positive");
    }
}
