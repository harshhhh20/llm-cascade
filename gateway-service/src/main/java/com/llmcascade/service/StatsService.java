package com.llmcascade.service;

import com.llmcascade.dto.RouteStat;
import com.llmcascade.dto.StatsResponse;
import com.llmcascade.repository.RequestLogRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class StatsService {

    private static final Set<String> FRONTIER_ROUTES = Set.of(
        "frontier_model", "baseline_frontier", "rule_based_escalated_frontier",
        "local_model_fallback_frontier"
    );

    private final RequestLogRepository repository;
    private final FrontierModelProvider frontierModel;

    public StatsService(RequestLogRepository repository, FrontierModelProvider frontierModel) {
        this.repository = repository;
        this.frontierModel = frontierModel;
    }

    public StatsResponse getStats() {
        var rows = repository.getRouteStats();

        long totalRequests = 0;
        double totalCost = 0;
        long totalTokens = 0;
        long frontierSuccessCount = 0;
        double frontierSuccessCostSum = 0;
        List<RouteStat> routeStats = new ArrayList<>();

        for (var row : rows) {
            long count = row.getCount() == null ? 0 : row.getCount();
            double avgLatency = row.getAvgLatencyMs() == null ? 0 : row.getAvgLatencyMs();
            double routeCost = row.getTotalCost() == null ? 0 : row.getTotalCost();
            long promptTokens = row.getPromptTokens() == null ? 0 : row.getPromptTokens();
            long completionTokens = row.getCompletionTokens() == null ? 0 : row.getCompletionTokens();

            totalRequests += count;
            totalCost += routeCost;
            totalTokens += (promptTokens + completionTokens);
            
            routeStats.add(new RouteStat(row.getRoute(), count, avgLatency, routeCost, promptTokens, completionTokens));

            if (FRONTIER_ROUTES.contains(row.getRoute()) && routeCost > 0) {
                frontierSuccessCount += count;
                frontierSuccessCostSum += routeCost;
            }
        }

        double baselineCost;
        String costSavedBasis;

        if (frontierSuccessCount > 0) {
            double avgRealFrontierCost = frontierSuccessCostSum / frontierSuccessCount;
            baselineCost = totalRequests * avgRealFrontierCost;
            costSavedBasis = "measured_from_" + frontierSuccessCount + "_frontier_calls";
        } else {
            baselineCost = totalRequests * frontierModel.estimateCostPerRequest();
            costSavedBasis = "estimated_no_frontier_data_yet";
        }

        Double costSavedPct = baselineCost > 0 ? (1 - totalCost / baselineCost) * 100 : null;

        return new StatsResponse(totalRequests, totalCost, baselineCost, costSavedPct, costSavedBasis, totalTokens, routeStats);
    }
}
