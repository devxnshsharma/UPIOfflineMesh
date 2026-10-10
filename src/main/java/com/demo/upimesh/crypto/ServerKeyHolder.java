package com.demo.upimesh.crypto;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * The backend's RSA-2048 key pair, generated in memory at every startup.
 *
 * No key material is stored in the source tree or on disk. The private key never leaves this
 * class except to {@link HybridCryptoService#decrypt}; only the public key is exposed
 * (GET /api/server-key). Production would keep the private key in an HSM / KMS.
 */
@Component
public class ServerKeyHolder {

    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    private KeyPair keyPair;

    @PostConstruct
    public void init() throws NoSuchAlgorithmException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        log.info("Server RSA-2048 key pair generated. Public key SHA-256: {}", publicKeyFingerprint());
    }

    public PublicKey getPublicKey() { return keyPair.getPublic(); }

    PrivateKey getPrivateKey() { return keyPair.getPrivate(); }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public String publicKeyFingerprint() {
        return Hashing.sha256Hex(getPublicKeyBase64());
    }
}
