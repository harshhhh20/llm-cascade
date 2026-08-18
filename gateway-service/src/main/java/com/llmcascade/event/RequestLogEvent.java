package com.llmcascade.event;

import java.util.UUID;

public record RequestLogEvent(
    UUID id,
    String traceId,
    String query,
    String route,
    String modelUsed,
    boolean cacheHit,
    Double cacheSimilarity,
    long latencyMs,
    double estimatedCostUsd
) {}

