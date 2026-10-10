package com.demo.upimesh.dto;

/** Partial update: null fields keep their current value. Range checks live in MeshSettings. */
public record SettingsRequest(Integer defaultTtl, Double lossProbability, Integer delayRounds,
                              Long packetExpirySeconds, Long seed) {}
