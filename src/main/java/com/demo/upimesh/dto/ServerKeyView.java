package com.demo.upimesh.dto;

public record ServerKeyView(String publicKey, String fingerprint, String algorithm, String hybridScheme) {}
