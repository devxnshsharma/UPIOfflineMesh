package com.demo.upimesh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * @param runs   independent simulations per parameter value (the sample count)
 * @param seed   base RNG seed; the same request with the same seed reproduces the same result
 * @param values parameter values to sweep (null = the experiment's defaults)
 */
public record ExperimentRequest(
        @NotNull Type type,
        @Min(1) @Max(100) Integer runs,
        Long seed,
        List<Double> values) {

    public enum Type { CONNECTIVITY_DELAY, PACKET_LOSS, TTL, GOSSIP_ROUNDS, BRIDGE_AVAILABILITY, DUPLICATE_DELIVERY }
}
