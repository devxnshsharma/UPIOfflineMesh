package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;

import java.math.BigDecimal;
import java.util.UUID;

/** Builds packets the way a sender phone would, for tests that talk to the backend directly. */
@TestComponent
public class TestPayments {

    @Autowired private HybridCryptoService crypto;
    @Autowired private ServerKeyHolder keys;

    public PaymentInstruction instruction(String sender, String receiver, String amount) {
        return instruction(sender, receiver, amount, System.currentTimeMillis());
    }

    public PaymentInstruction instruction(String sender, String receiver, String amount, long signedAt) {
        return new PaymentInstruction(sender, receiver, new BigDecimal(amount),
                UUID.randomUUID().toString(), signedAt);
    }

    public MeshPacket wrap(PaymentInstruction instruction) {
        return new MeshPacket(UUID.randomUUID().toString(), 5, System.currentTimeMillis(),
                crypto.encrypt(instruction, keys.getPublicKey()));
    }

    public MeshPacket packet(String sender, String receiver, String amount) {
        return wrap(instruction(sender, receiver, amount));
    }
}
