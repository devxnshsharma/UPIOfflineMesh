package com.demo.upimesh.dto;

/**
 * Liveness answer for GET /api/health: the process is up, the database answers, the mesh exists.
 * The last three fields only describe the deployment policy so the dashboard can adapt; they carry no secrets.
 *
 * @param mode          "local" (everything open) or "hosted" (admin actions protected, limits on)
 * @param adminRequired true when reset, simulation settings and topology changes need the admin token
 * @param h2Console     true when the developer database console is reachable (never in hosted mode)
 */
public record HealthView(String status, String database, int devices, long uptimeSeconds,
                         String mode, boolean adminRequired, boolean h2Console) {}
