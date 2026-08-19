package com.llmcascade.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class FrontierModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(FrontierModelProvider.class);

    private final RestTemplate restTemplate = new RestTemplate();
    private final String apiKey;

    private final double promptPricePerMillion;
    private final double completionPricePerMillion;

    public FrontierModelProvider(
            @Value("${frontier.api-key}") String apiKey,
            @Value("${frontier.prompt-price-per-million-tokens:0.075}") double promptPricePerMillion,
            @Value("${frontier.completion-price-per-million-tokens:0.30}") double completionPricePerMillion) {
        this.apiKey = apiKey;
        this.promptPricePerMillion = promptPricePerMillion;
        this.completionPricePerMillion = completionPricePerMillion;
    }

    @Retry(name = "frontier", fallbackMethod = "generateFallback")
    @CircuitBreaker(name = "frontier", fallbackMethod = "generateFallback")
    @Override
    @SuppressWarnings("unchecked")
    public GenerationResult generate(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
            "contents", List.of(Map.of(
                "parts", List.of(Map.of("text", prompt))
            ))
        );

        String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-pro:generateContent?key=" + apiKey;

        try {
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);

            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            String text = (String) parts.get(0).get("text");

            Map<String, Object> usageMetadata = (Map<String, Object>) response.get("usageMetadata");
            int promptTokens = usageMetadata != null && usageMetadata.get("promptTokenCount") != null
                ? ((Number) usageMetadata.get("promptTokenCount")).intValue() : 0;
            int completionTokens = usageMetadata != null && usageMetadata.get("candidatesTokenCount") != null
                ? ((Number) usageMetadata.get("candidatesTokenCount")).intValue() : 0;

            return new GenerationResult(text, promptTokens, completionTokens);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RateLimitedException("Frontier provider rate-limited the request", e);
        } catch (HttpClientErrorException e) {
            throw new FrontierClientException("Frontier provider rejected the request: " + e.getStatusCode(), e);
        } catch (HttpServerErrorException e) {
            throw new FrontierUnavailableException("Frontier provider returned " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new FrontierUnavailableException("Frontier provider unreachable", e);
        }
    }

    @SuppressWarnings("unused")
    private GenerationResult generateFallback(String prompt, RateLimitedException e) {
        log.warn("Frontier call exhausted retries due to rate limiting: {}", e.getMessage());
        throw e;
    }

    @SuppressWarnings("unused")
    private GenerationResult generateFallback(String prompt, Throwable e) {
        log.warn("Frontier call failed after retries/circuit breaker: {}", e.toString());
        if (e instanceof RuntimeException re) throw re;
        throw new FrontierUnavailableException("Frontier provider failed", e);
    }

    @Override
    public String tierName() { return "frontier_model"; }

    @Override
    public String modelIdentifier() { return "gemini-1.5-pro"; }

    @Override
    public double estimateCostPerRequest() {
        return (200 / 1_000_000.0) * promptPricePerMillion + (300 / 1_000_000.0) * completionPricePerMillion;
    }

    @Override
    public double computeCost(GenerationResult result) {
        return (result.promptTokens() / 1_000_000.0) * promptPricePerMillion
             + (result.completionTokens() / 1_000_000.0) * completionPricePerMillion;
    }
}

