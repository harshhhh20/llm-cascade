package com.llmcascade.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.Map;

@Component
public class LocalModelProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalModelProvider.class);
    private static final int DEFAULT_TIMEOUT_MS = 20_000;

    private final RestTemplate restTemplate;
    private final String ollamaUrl;

    @org.springframework.beans.factory.annotation.Autowired
    public LocalModelProvider(@Value("${ollama.url}") String ollamaUrl) {
        this(ollamaUrl, DEFAULT_TIMEOUT_MS);
    }

    LocalModelProvider(String ollamaUrl, int timeoutMs) {
        this.ollamaUrl = ollamaUrl;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    @PostConstruct
    public void warmUp() {
        try {
            log.info("Warming up local model...");
            generate("hello");
            log.info("Local model warm-up succeeded.");
        } catch (Exception e) {
            log.warn("Local model warm-up failed (will rely on runtime fallback): {}", e.getMessage());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public GenerationResult generate(String prompt) {
        try {
            Map<String, Object> body = Map.of(
                "model", "qwen2.5:1.5b",
                "prompt", prompt,
                "stream", false,
                "keep_alive", "10m"
            );
            Map<String, Object> response = restTemplate.postForObject(
                ollamaUrl + "/api/generate", body, Map.class);
                
            String text = (String) response.get("response");
            int promptTokens = response.get("prompt_eval_count") != null ? ((Number) response.get("prompt_eval_count")).intValue() : 0;
            int completionTokens = response.get("eval_count") != null ? ((Number) response.get("eval_count")).intValue() : 0;
            return new GenerationResult(text, promptTokens, completionTokens);
        } catch (RestClientException e) {
            log.warn("Local model call failed: {}", describeFailure(e));
            throw new ModelUnavailableException("Local model timed out or cold — fall back to frontier", e);
        }
    }

    private String describeFailure(RestClientException e) {
        if (e instanceof ResourceAccessException && e.getCause() instanceof SocketTimeoutException ste) {
            String msg = ste.getMessage() == null ? "" : ste.getMessage().toLowerCase();
            if (msg.contains("connect")) return "connect timeout — Ollama unreachable";
            if (msg.contains("read")) return "read timeout — Ollama reachable but too slow to respond in time";
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @Override
    public String tierName() { return "local_model"; }

    @Override
    public String modelIdentifier() { return "qwen2.5:1.5b"; }

    @Override
    public double estimateCostPerRequest() { return 0.0001; }

    @Override
    public double computeCost(GenerationResult result) { return 0.0001; }
}
