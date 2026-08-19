package com.llmcascade.dto;

import java.util.List;

public record StatsResponse(
    long totalRequests,
    double totalCostUsd,
    double estimatedBaselineCostUsd,
    Double costSavedPct,
    String costSavedBasis,
    long totalTokens,
    List<RouteStat> routes
) {}
