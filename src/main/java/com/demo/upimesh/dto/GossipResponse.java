package com.demo.upimesh.dto;

import com.demo.upimesh.mesh.GossipResult;

import java.util.List;

public record GossipResponse(int rounds, int transfers, int lost, int suppressed, int expired, List<GossipResult> perRound) {}
