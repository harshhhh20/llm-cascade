package com.llmcascade.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class ClassifierClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String mlServiceUrl;

    public ClassifierClient(@Value("${ml-service.url}") String mlServiceUrl) {
        this.mlServiceUrl = mlServiceUrl;
    }

    public record Complexity(String bucket, double score) {}

    public Complexity classify(String query) {
        Map<String, Object> response = restTemplate.postForObject(
            mlServiceUrl + "/classify", Map.of("text", query), Map.class);
        return new Complexity(
            (String) response.get("complexity"),
            ((Number) response.get("score")).doubleValue());
    }
}

