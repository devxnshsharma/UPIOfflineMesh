package com.demo.upimesh.dto;

import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.model.Reason;

import java.util.List;
import java.util.Map;

public record FlushResponse(int uploadsAttempted, Map<Outcome, Long> summary, List<Upload> results) {

    public record Upload(String bridgeNode, String packetId, Outcome outcome, Reason reason, Long ledgerEntryId, String message) {}
}
