package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PacketRecordRepository extends JpaRepository<PacketRecord, Long> {

    Optional<PacketRecord> findByPacketHash(String packetHash);

    long countByState(PacketRecord.State state);

    List<PacketRecord> findByStateAndClaimedAtBefore(PacketRecord.State state, Instant cutoff);

    @Transactional
    long deleteByPacketHashAndState(String packetHash, PacketRecord.State state);

    @Transactional
    long deleteByState(PacketRecord.State state);
}
