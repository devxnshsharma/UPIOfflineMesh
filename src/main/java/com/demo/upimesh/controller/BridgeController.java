package com.demo.upimesh.controller;

import com.demo.upimesh.dto.IngestResult;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.service.BridgeIngestionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The production-shaped entry point a real bridge phone would call once it has internet.
 *
 * Status codes tell the bridge what to do next:
 *   200 SETTLED / DUPLICATE_DROPPED  done, drop the packet
 *   422 REJECTED                     permanent, drop the packet
 *   503 FAILED                       transient, keep the packet and retry
 *   400                              the request itself is malformed
 */
@RestController
@RequestMapping("/api/bridge")
public class BridgeController {

    private final BridgeIngestionService ingestion;

    public BridgeController(BridgeIngestionService ingestion) {
        this.ingestion = ingestion;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResult> ingest(
            @Valid @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", defaultValue = "0") int hopCount) {
        IngestResult r = ingestion.ingest(packet, bridgeNodeId, hopCount);
        return switch (r.outcome()) {
            case SETTLED, DUPLICATE_DROPPED -> ResponseEntity.ok(r);
            case REJECTED -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(r);
            case FAILED -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1").body(r);
        };
    }
}
