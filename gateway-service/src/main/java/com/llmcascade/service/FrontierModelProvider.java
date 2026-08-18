package com.llmcascade.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class FrontierModelProvider implements ModelProvider {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String apiKey;

    public FrontierModelProvider(@Value("${frontier.api-key}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String generate(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-goog-api-key", apiKey);

        Map<String, Object> body = Map.of(
            "contents", List.of(
                Map.of("parts", List.of(
                    Map.of("text", prompt)
                ))
            )
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent";
        
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
                
                List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
                Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
                List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
                return (String) parts.get(0).get("text");
            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                if (attempt == 5) throw e;
                try { Thread.sleep(attempt * 4000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (Exception e) {
                throw new RuntimeException("Frontier API failed: " + e.getMessage(), e);
            }
        }
        throw new RuntimeException("Frontier model failed after 5 retries");
    }

    @Override
    public String tierName() { return "frontier_model"; }

    @Override
    public String modelIdentifier() { return "gemini-3.5-flash-lite"; }

    @Override
    public double costPerRequest() { return 0.015; } // rough estimate â€” replace with real per-token pricing math
}

