package com.demo.upimesh.mesh;

/**
 * Summary of one gossip round.
 *
 * @param transfers  transmissions actually sent
 * @param lost       transmissions lost (the sender keeps its copy and retries)
 * @param suppressed offers skipped because the neighbour already held, or was already receiving, the packet
 * @param expired    copies that expired this round
 */
public record GossipResult(int round, int transfers, int lost, int suppressed, int expired) {}
