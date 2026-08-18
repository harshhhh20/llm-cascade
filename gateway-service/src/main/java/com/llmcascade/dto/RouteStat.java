package com.llmcascade.dto;

public record RouteStat(String route, long count, double avgLatencyMs, double totalCostUsd) {}

