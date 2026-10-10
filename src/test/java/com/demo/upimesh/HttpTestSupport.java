package com.demo.upimesh;

import com.demo.upimesh.model.*;
import com.demo.upimesh.service.ResetService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Shared plumbing for tests that drive the application through its HTTP API. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestPayments.class)
abstract class HttpTestSupport {

    record Resp(int status, JsonNode body, MockHttpServletResponse raw) {}

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected ResetService reset;
    @Autowired protected AccountRepository accounts;
    @Autowired protected LedgerEntryRepository ledger;
    @Autowired protected PacketRecordRepository packets;
    @Autowired protected IngestEventRepository events;
    @Autowired protected TestPayments pay;

    @BeforeEach
    void baseline() {
        reset.reset();
    }

    protected Resp call(MockHttpServletRequestBuilder req, Object body) throws Exception {
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        MockHttpServletResponse r = mvc.perform(req).andReturn().getResponse();
        String text = r.getContentAsString();
        JsonNode node = text.isBlank() || !(text.startsWith("{") || text.startsWith("[")) ? json.nullNode() : json.readTree(text);
        return new Resp(r.getStatus(), node, r);
    }

    protected Resp getJson(String path) throws Exception { return call(MockMvcRequestBuilders.get(path), null); }
    protected Resp postJson(String path, Object body) throws Exception { return call(MockMvcRequestBuilders.post(path), body); }
    protected Resp patchJson(String path, Object body) throws Exception { return call(MockMvcRequestBuilders.patch(path), body); }
    protected Resp putJson(String path, Object body) throws Exception { return call(MockMvcRequestBuilders.put(path), body); }

    protected JsonNode send(String from, String to, String amount, String mode, Integer ttl) throws Exception {
        var body = json.createObjectNode().put("senderVpa", from).put("receiverVpa", to).put("amount", new BigDecimal(amount));
        if (mode != null) body.put("mode", mode);
        if (ttl != null) body.put("ttl", ttl);
        Resp r = postJson("/api/demo/send", body);
        org.junit.jupiter.api.Assertions.assertEquals(200, r.status(), r.raw().getContentAsString());
        return r.body();
    }

    protected JsonNode gossip(int rounds) throws Exception {
        return postJson("/api/mesh/gossip", json.createObjectNode().put("rounds", rounds)).body();
    }

    protected JsonNode flush() throws Exception {
        Resp r = postJson("/api/mesh/flush", null);
        org.junit.jupiter.api.Assertions.assertEquals(200, r.status());
        return r.body();
    }

    protected void online(String bridge, boolean on) throws Exception {
        Resp r = patchJson("/api/mesh/devices/" + bridge, json.createObjectNode().put("online", on));
        org.junit.jupiter.api.Assertions.assertEquals(200, r.status(), r.raw().getContentAsString());
    }

    protected void allBridgesOnline() throws Exception {
        for (String b : List.of("bridge-1", "bridge-2", "bridge-3")) online(b, true);
    }

    protected String status(String packetId) throws Exception {
        return getJson("/api/mesh/packets/" + packetId).body().get("status").asText();
    }

    protected List<String> holders(String packetId) throws Exception {
        List<String> out = new ArrayList<>();
        getJson("/api/mesh/packets/" + packetId).body().get("holders").forEach(h -> out.add(h.get("deviceId").asText()));
        return out;
    }

    protected BigDecimal balance(String vpa) {
        return accounts.findById(vpa).orElseThrow().getBalance();
    }

    protected long count(JsonNode flushResult, String outcome) {
        long n = 0;
        for (JsonNode u : flushResult.get("results")) if (outcome.equals(u.get("outcome").asText())) n++;
        return n;
    }
}
