package com.demo.upimesh.dto;

import jakarta.validation.constraints.*;

public record ReplayRequest(
        @NotBlank String packetId,
        @NotNull Kind kind,
        @Min(1) @Max(50) Integer times) {

    public enum Kind {
        /** Submit the exact same bytes to the backend several times at once. */
        IDENTICAL,
        /** Re-encrypt the same logical payment (same nonce) into a brand-new packet and inject it. */
        REPACKAGED
    }
}
