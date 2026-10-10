package com.demo.upimesh.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

public record DemoSendRequest(
        @NotBlank String senderVpa,
        @NotBlank String receiverVpa,
        @NotNull @Positive @Digits(integer = 12, fraction = 2) BigDecimal amount,
        @Min(1) @Max(32) Integer ttl,
        String startDevice,
        SendMode mode) {}
