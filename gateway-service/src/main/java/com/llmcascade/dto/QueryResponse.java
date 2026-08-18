package com.llmcascade.dto;

public record QueryResponse(
    String answer,
    String route,
    String modelUsed,
    long latencyMs,
    double estimatedCostUsd,
    Double cacheSimilarity,
    String traceId
) {}

