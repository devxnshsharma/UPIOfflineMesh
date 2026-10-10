package com.demo.upimesh.controller;

import com.demo.upimesh.config.AdminOnly;
import com.demo.upimesh.dto.*;
import com.demo.upimesh.mesh.MeshSettings;
import com.demo.upimesh.mesh.MeshSnapshot;
import com.demo.upimesh.mesh.PacketDetail;
import com.demo.upimesh.service.MeshSimulatorService;
import com.demo.upimesh.service.NotFoundException;
import com.demo.upimesh.service.ResetService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/mesh")
public class MeshController {

    private final MeshSimulatorService mesh;
    private final ResetService reset;

    public MeshController(MeshSimulatorService mesh, ResetService reset) {
        this.mesh = mesh;
        this.reset = reset;
    }

    @GetMapping("/state")
    public MeshSnapshot state() {
        return mesh.snapshot();
    }

    @PostMapping("/gossip")
    public GossipResponse gossip(@Valid @RequestBody(required = false) GossipRequest request) {
        return mesh.gossip(request == null || request.rounds() == null ? 1 : request.rounds());
    }

    /** Online bridges upload every packet they hold. */
    @PostMapping("/flush")
    public FlushResponse flush() {
        return mesh.flush();
    }

    /** Restores accounts, ledger, idempotency state and the mesh to the reproducible baseline. */
    @PostMapping("/reset")
    @AdminOnly
    public Map<String, String> reset(@RequestBody(required = false) ResetRequest request) {
        reset.reset(request == null ? null : request.topology());
        return Map.of("status", "reset", "topology", mesh.snapshot().topology());
    }

    @PatchMapping("/devices/{id}")
    public MeshSnapshot.DeviceView device(@PathVariable String id, @RequestBody DeviceUpdateRequest request) {
        return mesh.updateDevice(id, request);
    }

    @GetMapping("/settings")
    public MeshSettings settings() {
        return mesh.settings();
    }

    @PutMapping("/settings")
    @AdminOnly
    public MeshSettings updateSettings(@RequestBody SettingsRequest request) {
        return mesh.updateSettings(request);
    }

    @GetMapping("/packets/{packetId}")
    public PacketDetail packet(@PathVariable String packetId) {
        return mesh.detail(packetId).orElseThrow(() -> new NotFoundException("Unknown packet: " + packetId));
    }
}
