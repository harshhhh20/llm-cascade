package com.llmcascade.service;

public interface ModelProvider {
    String generate(String prompt);
    String tierName();          // route category, e.g. "local_model" / "frontier_model"
    String modelIdentifier();   // actual model id used in logs/response, e.g. "phi3:mini"
    double costPerRequest();
}

