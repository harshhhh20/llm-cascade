package com.llmcascade.dto;

// forceFrontier: set true by the eval harness only, to get a true
// "always call the frontier model" baseline for comparison. Bypasses
// optimizer/cache/classifier entirely â€” never set this from normal traffic.
public record QueryRequest(String query, String userId, Boolean forceFrontier) {
    public QueryRequest(String query, String userId) {
        this(query, userId, false);
    }
}

