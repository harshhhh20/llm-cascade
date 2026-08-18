package com.llmcascade.service;

import com.llmcascade.dto.RouteStat;
import com.llmcascade.dto.StatsResponse;
import com.llmcascade.repository.RequestLogRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StatsService {

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
        List<RouteStat> routeStats = new ArrayList<>();

        for (var row : rows) {
            long count = row.getCount() == null ? 0 : row.getCount();
            double avgLatency = row.getAvgLatencyMs() == null ? 0 : row.getAvgLatencyMs();
            double routeCost = row.getTotalCost() == null ? 0 : row.getTotalCost();

            totalRequests += count;
            totalCost += routeCost;
            routeStats.add(new RouteStat(row.getRoute(), count, avgLatency, routeCost));
        }

        // Baseline = what it would have cost if every request had gone straight
        // to the frontier model, with no routing/caching/rules at all.
        double baselineCost = totalRequests * frontierModel.costPerRequest();
        double costSavedPct = baselineCost > 0 ? (1 - totalCost / baselineCost) * 100 : 0;

        return new StatsResponse(totalRequests, totalCost, baselineCost, costSavedPct, routeStats);
    }
}

