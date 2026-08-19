package com.llmcascade.repository;

import com.llmcascade.entity.RequestLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RequestLogRepository extends JpaRepository<RequestLogEntity, UUID> {

    @Query("SELECT r.route as route, COUNT(r) as count, AVG(r.latencyMs) as avgLatencyMs, " +
           "SUM(r.estimatedCostUsd) as totalCost, " +
           "SUM(r.promptTokens) as promptTokens, " +
           "SUM(r.completionTokens) as completionTokens " +
           "FROM RequestLogEntity r GROUP BY r.route")
    List<RouteStatProjection> getRouteStats();

    interface RouteStatProjection {
        String getRoute();
        Long getCount();
        Double getAvgLatencyMs();
        Double getTotalCost();
        Long getPromptTokens();
        Long getCompletionTokens();
    }
}
