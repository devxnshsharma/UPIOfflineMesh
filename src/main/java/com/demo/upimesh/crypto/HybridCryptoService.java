package com.demo.upimesh.crypto;

import com.demo.upimesh.model.PaymentInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

/**
 * Hybrid encryption: AES-256-GCM protects the payload, RSA-OAEP(SHA-256) protects the AES key.
 *
 * Wire format (then Base64): [256 B RSA-OAEP(AES key)] [12 B GCM IV] [AES-GCM ciphertext || 16 B tag]
 *
 * Sender side needs only {@link #encrypt} and the public key. Only the backend can call
 * {@link #decrypt}. Relays and bridges never receive this service - they carry opaque Base64.
 */
@Service
public class HybridCryptoService {

    private static final String RSA = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final String AES = "AES/GCM/NoPadding";
    private static final int AES_KEY_BITS = 256, RSA_BLOCK = 256, IV_BYTES = 12, TAG_BITS = 128;

    private final SecureRandom rng = new SecureRandom();
    private final ObjectMapper json = new ObjectMapper();
    private final ServerKeyHolder serverKey;

    public HybridCryptoService(ServerKeyHolder serverKey) {
        this.serverKey = serverKey;
    }

    public byte[] serialize(PaymentInstruction instruction) {
        try {
            return json.writeValueAsBytes(instruction);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise payment instruction", e);
        }
    }

    /** Sender side: encrypt for the server public key. Fresh AES key and IV for every call. */
    public String encrypt(PaymentInstruction instruction, PublicKey serverPublicKey) {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(AES_KEY_BITS, rng);
            SecretKey aesKey = kg.generateKey();

            byte[] iv = new byte[IV_BYTES];
            rng.nextBytes(iv);
            Cipher aes = Cipher.getInstance(AES);
            aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = aes.doFinal(serialize(instruction));

            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.ENCRYPT_MODE, serverPublicKey, oaep(), rng);
            byte[] wrappedKey = rsa.doFinal(aesKey.getEncoded());

            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(wrappedKey.length + IV_BYTES + sealed.length)
                            .put(wrappedKey).put(iv).put(sealed).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Backend side. Any malformed, truncated, wrong-key or tampered input throws {@link CryptoException}. */
    public PaymentInstruction decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) {
            throw new CryptoException("Empty ciphertext");
        }
        byte[] plaintext;
        try {
            byte[] all = Base64.getDecoder().decode(base64Ciphertext);
            if (all.length < RSA_BLOCK + IV_BYTES + TAG_BITS / 8) {
                throw new CryptoException("Ciphertext too short");
            }
            ByteBuffer buf = ByteBuffer.wrap(all);
            byte[] wrappedKey = new byte[RSA_BLOCK], iv = new byte[IV_BYTES];
            buf.get(wrappedKey).get(iv);
            byte[] sealed = new byte[buf.remaining()];
            buf.get(sealed);

            Cipher rsa = Cipher.getInstance(RSA);
            rsa.init(Cipher.DECRYPT_MODE, serverKey.getPrivateKey(), oaep());
            SecretKey aesKey = new SecretKeySpec(rsa.doFinal(wrappedKey), "AES");

            Cipher aes = Cipher.getInstance(AES);
            aes.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            plaintext = aes.doFinal(sealed); // throws AEADBadTagException if one bit changed
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new CryptoException("Decryption failed: " + e.getClass().getSimpleName(), e);
        }
        try {
            return json.readValue(plaintext, PaymentInstruction.class);
        } catch (IOException e) {
            throw new MalformedPayloadException("Authenticated payload is not a payment instruction", e);
        }
    }

    /** SHA-256 of the ciphertext text: the packet fingerprint and first idempotency key. */
    public String sha256Hex(String ciphertext) {
        return Hashing.sha256Hex(ciphertext);
    }

    /** Demo attack: a malicious relay flips one byte inside the AES ciphertext region. */
    public String flipOneByte(String base64Ciphertext) {
        byte[] all = Base64.getDecoder().decode(base64Ciphertext);
        int pos = RSA_BLOCK + IV_BYTES + (all.length - RSA_BLOCK - IV_BYTES) / 2;
        all[pos] ^= 0x01;
        return Base64.getEncoder().encodeToString(all);
    }

    private static OAEPParameterSpec oaep() {
        return new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    }
}
