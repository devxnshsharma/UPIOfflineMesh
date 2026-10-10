package com.demo.upimesh.service;

import com.demo.upimesh.dto.*;
import com.demo.upimesh.mesh.MeshSnapshot;
import com.demo.upimesh.model.*;
import org.springframework.stereotype.Service;

@Service
public class StatsService {

    private final MeshSimulatorService mesh;
    private final IngestEventRepository events;

    public StatsService(MeshSimulatorService mesh, IngestEventRepository events) {
        this.mesh = mesh;
        this.events = events;
    }

    public StatsView stats() {
        MeshSnapshot.Counters c = mesh.snapshot().counters();
        return new StatsView(c.devicesTotal(), c.devicesActive(), c.bridgesTotal(), c.bridgesOnline(), c.awaitingDelivery(),
                c.forwarded(), c.lost(), c.suppressed(),
                events.countByOutcomeAndSimulationFalse(Outcome.SETTLED),
                events.countByOutcomeAndSimulationFalse(Outcome.DUPLICATE_DROPPED),
                events.countByOutcomeAndSimulationFalse(Outcome.REJECTED),
                events.countByReasonAndSimulationFalse(Reason.NONCE_REUSED),
                events.countByOutcomeAndSimulationFalse(Outcome.FAILED));
    }
}
