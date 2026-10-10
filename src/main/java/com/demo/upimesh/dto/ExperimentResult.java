package com.demo.upimesh.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Measured output of one experiment sweep plus every parameter needed to reproduce it. */
public record ExperimentResult(String id, Instant ranAt, long durationMs, ExperimentRequest.Type type, String title,
                               String parameterName, String parameterUnit, Map<String, Object> parameters,
                               List<MetricInfo> metrics, List<Row> rows) {

    public record MetricInfo(String key, String label, String unit) {}

    /** One parameter value: how many runs it averaged over and the resulting metrics. */
    public record Row(double param, int samples, Map<String, Double> metrics) {}
}
