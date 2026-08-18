package com.llmcascade.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class EmbeddingClient {

    private final RestTemplate restTemplate = new RestTemplate();
    private final String mlServiceUrl;

    public EmbeddingClient(@Value("${ml-service.url}") String mlServiceUrl) {
        this.mlServiceUrl = mlServiceUrl;
    }

    @SuppressWarnings("unchecked")
    public float[] embed(String text) {
        Map<String, Object> response = restTemplate.postForObject(
            mlServiceUrl + "/embed", Map.of("text", text), Map.class);
        List<Double> raw = (List<Double>) response.get("embedding");
        float[] result = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) result[i] = raw.get(i).floatValue();
        return result;
    }
}

