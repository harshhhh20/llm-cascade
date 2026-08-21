package com.llmcascade.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class PromptOptimizerClient {

    private final RestTemplate restTemplate;
    private final String mlServiceUrl;

    public PromptOptimizerClient(@Value("${ml-services.url}") String mlServiceUrl) {
        org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
        this.mlServiceUrl = mlServiceUrl;
    }

    public record OptimizedQuery(String text, boolean rejected) {}

    public OptimizedQuery optimize(String rawQuery) {
        Map<String, Object> response = restTemplate.postForObject(
            mlServiceUrl + "/optimize", Map.of("raw_query", rawQuery), Map.class);
        boolean rejected = Boolean.TRUE.equals(response.get("rejected"));
        return new OptimizedQuery((String) response.get("optimized_query"), rejected);
    }
}


