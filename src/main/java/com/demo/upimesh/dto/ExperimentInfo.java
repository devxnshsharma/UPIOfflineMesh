package com.demo.upimesh.dto;

import java.util.List;

/**
 * Describes an available experiment so the UI can build and validate its form.
 *
 * @param valueMin       smallest accepted parameter value
 * @param valueMax       largest accepted parameter value
 * @param wholeValues    true when only whole numbers are accepted
 * @param maxValues      most parameter values per request
 * @param maxRuns        most runs per value
 * @param maxSimulations budget: values x runs may not exceed this
 * @param maxSubmissions budget for the duplicate-delivery experiment: runs x sum(values) may not exceed this
 */
public record ExperimentInfo(ExperimentRequest.Type type, String title, String description, String parameterName,
                             String parameterUnit, List<Double> defaultValues, int defaultRuns,
                             List<ExperimentResult.MetricInfo> metrics,
                             double valueMin, double valueMax, boolean wholeValues,
                             int maxValues, int maxRuns, int maxSimulations, int maxSubmissions) {}
