package com.llmcascade.service;

public interface ModelProvider {
    GenerationResult generate(String prompt);
    String tierName();
    String modelIdentifier();

    double estimateCostPerRequest();
    double computeCost(GenerationResult result);
}
