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
    // Timeout set to 20s â€” measured worst-case for qwen2.5:1.5b was 15.6s on
    // this hardware (CPU-only Docker Desktop). 20s gives headroom without
    // waiting indefinitely. See eval/compare_local_models.py for the benchmark.
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

    // Fires once at app startup so Ollama has the model loaded before the
    // first real request hits it, instead of the first user query paying
    // the cold-load cost. Best-effort â€” if Ollama isn't up yet or the model
    // isn't pulled, this just logs and moves on; RouterService's fallback
    // still covers you either way.
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
    public String generate(String prompt) {
        try {
            Map<String, Object> body = Map.of(
                // qwen2.5:1.5b selected over phi3:mini (1.00 correctness, 20.9s avg)
                // and qwen2.5:0.5b (0.74 correctness, 3.3s avg) via direct benchmark:
                // 0.95 correctness at 6.5s avg â€” best correctness-per-second tradeoff.
                // See eval/compare_local_models.py to reproduce.
                "model", "qwen2.5:1.5b",
                "prompt", prompt,
                "stream", false,
                "keep_alive", "10m"  // keeps the model resident between requests â€”
                                     // without this, Ollama's default idle-unload
                                     // (a few minutes) can make EVERY request cold,
                                     // not just the first one
            );
            Map<String, Object> response = restTemplate.postForObject(
                ollamaUrl + "/api/generate", body, Map.class);
            return (String) response.get("response");
        } catch (RestClientException e) {
            log.warn("Local model call failed: {}", describeFailure(e));
            throw new ModelUnavailableException("Local model timed out or cold â€” fall back to frontier", e);
        }
    }

    // Distinguishes "couldn't reach Ollama at all" from "reached it but it
    // took too long to respond" â€” the fix for each is different (container
    // not up / not on the network, vs. timeout too tight or model still cold).
    private String describeFailure(RestClientException e) {
        if (e instanceof ResourceAccessException && e.getCause() instanceof SocketTimeoutException ste) {
            String msg = ste.getMessage() == null ? "" : ste.getMessage().toLowerCase();
            if (msg.contains("connect")) return "connect timeout â€” Ollama unreachable";
            if (msg.contains("read")) return "read timeout â€” Ollama reachable but too slow to respond in time";
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    @Override
    public String tierName() { return "local_model"; }

    @Override
    public String modelIdentifier() { return "qwen2.5:1.5b"; }

    @Override
    public double costPerRequest() { return 0.0001; }
}

