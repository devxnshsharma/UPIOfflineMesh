package com.demo.upimesh.crypto;

/** Any failure to open a packet: bad format, wrong key, or failed GCM authentication. */
public class CryptoException extends RuntimeException {
    public CryptoException(String message) { super(message); }
    public CryptoException(String message, Throwable cause) { super(message, cause); }
}
