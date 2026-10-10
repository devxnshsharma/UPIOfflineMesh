package com.demo.upimesh.crypto;

/** The packet authenticated fine, but the plaintext inside is not a payment instruction. */
public class MalformedPayloadException extends CryptoException {
    public MalformedPayloadException(String message, Throwable cause) { super(message, cause); }
}
