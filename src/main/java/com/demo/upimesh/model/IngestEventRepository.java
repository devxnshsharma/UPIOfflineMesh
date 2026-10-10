package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IngestEventRepository extends JpaRepository<IngestEvent, Long> {
    List<IngestEvent> findTop50BySimulationFalseOrderByIdDesc();
    long countByOutcomeAndSimulationFalse(Outcome outcome);
    long countByOutcomeAndSimulationTrue(Outcome outcome);
    long countByReasonAndSimulationFalse(Reason reason);
}
