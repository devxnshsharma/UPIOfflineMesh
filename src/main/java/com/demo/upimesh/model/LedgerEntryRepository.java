package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    boolean existsByNonce(String nonce);
    Optional<LedgerEntry> findByPacketHash(String packetHash);
}
