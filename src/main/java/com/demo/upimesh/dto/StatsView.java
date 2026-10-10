package com.demo.upimesh.dto;

/** Overview tiles. Every number is read from live simulator state or persisted records. */
public record StatsView(int devicesTotal, int devicesActive, int bridgesTotal, int bridgesOnline,
                        int packetsAwaiting, long packetsForwarded, long transmissionsLost, long duplicatesSuppressedInMesh,
                        long settlements, long duplicatesDropped, long invalidRejected, long replaysRejected, long failed) {}
