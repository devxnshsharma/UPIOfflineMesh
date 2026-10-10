package com.demo.upimesh.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Optional body of POST /api/mesh/gossip: how many rounds to run (default 1). */
public record GossipRequest(@Min(1) @Max(50) Integer rounds) {}
