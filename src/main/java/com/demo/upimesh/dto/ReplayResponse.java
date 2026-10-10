package com.demo.upimesh.dto;

import com.demo.upimesh.model.Outcome;

import java.util.List;
import java.util.Map;

/** For IDENTICAL: the backend's answer to every submission. For REPACKAGED: the new packet that was injected. */
public record ReplayResponse(ReplayRequest.Kind kind, int submissions, Map<Outcome, Long> summary,
                             List<IngestResult> results, SendResponse injected) {}
