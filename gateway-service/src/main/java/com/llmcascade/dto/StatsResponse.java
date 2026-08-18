package com.llmcascade.dto;

import java.util.List;

public record StatsResponse(
    long totalRequests,
    double totalCostUsd,
    double estimatedBaselineCostUsd,
    double costSavedPct,
    List<RouteStat> routes
) {}

