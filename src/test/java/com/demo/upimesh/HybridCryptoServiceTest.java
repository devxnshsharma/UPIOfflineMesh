package com.demo.upimesh;

import com.demo.upimesh.crypto.CryptoException;
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.PaymentInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Pure crypto tests: no Spring context, no database. */
class HybridCryptoServiceTest {

    private static final int RSA_BYTES = 256, IV_BYTES = 12;

    private ServerKeyHolder keys;
    private HybridCryptoService crypto;

    @BeforeEach
    void setUp() throws Exception {
        keys = new ServerKeyHolder();
        keys.init();
        crypto = new HybridCryptoService(keys);
    }

    private PaymentInstruction sample() {
        return new PaymentInstruction("alice@demo", "bob@demo", new BigDecimal("123.45"),
                UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    @Test
    void encryptDecryptRoundTrip() {
        PaymentInstruction original = sample();
        PaymentInstruction back = crypto.decrypt(crypto.encrypt(original, keys.getPublicKey()));

        assertEquals(original.senderVpa(), back.senderVpa());
        assertEquals(original.receiverVpa(), back.receiverVpa());
        assertEquals(0, original.amount().compareTo(back.amount()));
        assertEquals(original.nonce(), back.nonce());
        assertEquals(original.signedAt(), back.signedAt());
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        byte[] raw = Base64.getDecoder().decode(crypto.encrypt(sample(), keys.getPublicKey()));
        String asText = new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains("alice@demo"));
        assertFalse(asText.contains("bob@demo"));
    }

    @Test
    void wireFormatIsRsaKeyThenIvThenCiphertextWithTag() {
        PaymentInstruction p = sample();
        byte[] raw = Base64.getDecoder().decode(crypto.encrypt(p, keys.getPublicKey()));
        int plaintextLen = crypto.serialize(p).length;
        assertEquals(RSA_BYTES + IV_BYTES + plaintextLen + 16, raw.length);
    }

    @Test
    void sameInstructionEncryptedTwiceGivesDifferentCiphertextAndHash() {
        PaymentInstruction p = sample();
        String a = crypto.encrypt(p, keys.getPublicKey());
        String b = crypto.encrypt(p, keys.getPublicKey());
        assertNotEquals(a, b, "fresh AES key + IV per packet");
        assertNotEquals(crypto.sha256Hex(a), crypto.sha256Hex(b));
        assertEquals(crypto.sha256Hex(a), crypto.sha256Hex(a));
        assertEquals(64, crypto.sha256Hex(a).length());
    }

    @Test
    void flippingAnyRegionOfTheCiphertextIsRejected() {
        byte[] raw = Base64.getDecoder().decode(crypto.encrypt(sample(), keys.getPublicKey()));
        int[] positions = {
                10,                                 // inside the RSA-protected AES key
                RSA_BYTES + 3,                      // inside the GCM IV
                RSA_BYTES + IV_BYTES + 5,           // inside the AES ciphertext
                raw.length - 1                      // inside the GCM authentication tag
        };
        for (int pos : positions) {
            byte[] tampered = raw.clone();
            tampered[pos] ^= 0x01;
            String b64 = Base64.getEncoder().encodeToString(tampered);
            assertThrows(CryptoException.class, () -> crypto.decrypt(b64), "byte " + pos);
        }
    }

    @Test
    void flipByteHelperChangesExactlyOneByte() {
        String original = crypto.encrypt(sample(), keys.getPublicKey());
        String tampered = crypto.flipOneByte(original);
        byte[] a = Base64.getDecoder().decode(original), b = Base64.getDecoder().decode(tampered);
        int diff = 0;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) diff++;
        assertEquals(1, diff);
        assertThrows(CryptoException.class, () -> crypto.decrypt(tampered));
    }

    @Test
    void invalidFormatsAreRejected() {
        assertThrows(CryptoException.class, () -> crypto.decrypt("not base64 !!!"));
        assertThrows(CryptoException.class, () -> crypto.decrypt(""));
        assertThrows(CryptoException.class, () -> crypto.decrypt(null));
        assertThrows(CryptoException.class, () ->
                crypto.decrypt(Base64.getEncoder().encodeToString(new byte[40])), "too short");
        assertThrows(CryptoException.class, () ->
                crypto.decrypt(Base64.getEncoder().encodeToString(new byte[RSA_BYTES + IV_BYTES + 16])),
                "right length, random content");
    }

    @Test
    void packetEncryptedForAnotherServerKeyCannotBeDecrypted() throws Exception {
        ServerKeyHolder other = new ServerKeyHolder();
        other.init();
        String foreign = crypto.encrypt(sample(), other.getPublicKey());
        assertThrows(CryptoException.class, () -> crypto.decrypt(foreign));
    }
}
