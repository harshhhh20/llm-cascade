package com.llmcascade.entity;

import com.llmcascade.event.RequestLogEvent;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "request_log")
public class RequestLogEntity {

    @Id
    private UUID id;

    private UUID traceId;

    @Column(columnDefinition = "TEXT")
    private String query;

    private String route;
    private String modelUsed;
    private boolean cacheHit;
    private Double cacheSimilarity;
    private Integer latencyMs;
    private Double estimatedCostUsd;
    private Instant createdAt = Instant.now();

    public static RequestLogEntity from(RequestLogEvent event) {
        RequestLogEntity e = new RequestLogEntity();
        e.id = event.id();
        e.traceId = UUID.fromString(event.traceId());
        e.query = event.query();
        e.route = event.route();
        e.modelUsed = event.modelUsed();
        e.cacheHit = event.cacheHit();
        e.cacheSimilarity = event.cacheSimilarity();
        e.latencyMs = (int) event.latencyMs();
        e.estimatedCostUsd = event.estimatedCostUsd();
        return e;
    }

    public UUID getId() { return id; }
    public UUID getTraceId() { return traceId; }
    public String getQuery() { return query; }
    public String getRoute() { return route; }
    public String getModelUsed() { return modelUsed; }
    public boolean isCacheHit() { return cacheHit; }
    public Double getCacheSimilarity() { return cacheSimilarity; }
    public Integer getLatencyMs() { return latencyMs; }
    public Double getEstimatedCostUsd() { return estimatedCostUsd; }
    public Instant getCreatedAt() { return createdAt; }
}

