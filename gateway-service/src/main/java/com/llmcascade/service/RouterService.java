package com.llmcascade.service;

import com.llmcascade.dto.QueryRequest;
import com.llmcascade.dto.QueryResponse;
import com.llmcascade.event.RequestLogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class RouterService {

    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private final PromptOptimizerClient optimizerClient;
    private final CacheService cacheService;
    private final ClassifierClient classifierClient;
    private final RuleBasedAnswerService ruleBasedAnswerService;
    private final LocalModelProvider localModel;
    private final FrontierModelProvider frontierModel;
    private final ApplicationEventPublisher eventPublisher;

    public RouterService(PromptOptimizerClient optimizerClient, CacheService cacheService,
                          ClassifierClient classifierClient, RuleBasedAnswerService ruleBasedAnswerService,
                          LocalModelProvider localModel, FrontierModelProvider frontierModel,
                          ApplicationEventPublisher eventPublisher) {
        this.optimizerClient = optimizerClient;
        this.cacheService = cacheService;
        this.classifierClient = classifierClient;
        this.ruleBasedAnswerService = ruleBasedAnswerService;
        this.localModel = localModel;
        this.frontierModel = frontierModel;
        this.eventPublisher = eventPublisher;
    }

    public QueryResponse handle(QueryRequest request, String traceId) {
        long start = System.currentTimeMillis();

        // Eval-only baseline path: skip optimizer/cache/classifier entirely so
        // the eval harness gets a true "always call frontier" cost/latency
        // number to compare adaptive routing against.
        if (Boolean.TRUE.equals(request.forceFrontier())) {
            String answer;
            try {
                answer = frontierModel.generate(request.query());
            } catch (Exception e) {
                log.error("Baseline frontier model failed: {}", e.getMessage());
                answer = "The frontier model is currently unavailable.";
            }
            return finish(request, traceId, "baseline_frontier", frontierModel.modelIdentifier(),
                start, frontierModel.costPerRequest(), null, answer);
        }

        // 1. Optimize + injection heuristic check (guardrails.py on the ml-service side)
        PromptOptimizerClient.OptimizedQuery optimized = optimizerClient.optimize(request.query());
        if (optimized.rejected()) {
            return finish(request, traceId, "rejected", null, start, 0.0, null,
                "This request was flagged and cannot be processed.");
        }

        // 2. Semantic cache check â€” zero LLM cost on a hit
        try {
            var cacheResult = cacheService.lookup(optimized.text());
            if (cacheResult.isPresent()) {
                var hit = cacheResult.get();
                return finish(request, traceId, "cache_hit", null, start, 0.0, hit.similarity(), hit.answer());
            }
        } catch (Exception e) {
            log.warn("Cache lookup failed (non-fatal, skipping cache): {}", e.getMessage());
        }

        // 3. Classify complexity
        ClassifierClient.Complexity complexity;
        try {
            complexity = classifierClient.classify(optimized.text());
        } catch (Exception e) {
            log.warn("Classifier failed, defaulting to 'hard' bucket: {}", e.getMessage());
            complexity = new ClassifierClient.Complexity("hard", 0.0);
        }

        // 4 + 5. Route + generate
        String answer;
        String route;
        String modelUsed;
        double cost;

        if ("trivial".equals(complexity.bucket())) {
            var ruleResult = ruleBasedAnswerService.tryAnswer(optimized.text());
            if (ruleResult.handled()) {
                answer = ruleResult.answer();
                route = "rule_based";
                modelUsed = null;
                cost = 0.0;
            } else {
                // Classifier said trivial but no rule confidently matched â€”
                // escalate rather than return a placeholder. Logged as its
                // own route so you can see how often this happens; a high
                // rate means the classifier's trivial bucket needs tightening.
                try {
                    answer = localModel.generate(optimized.text());
                    route = "rule_based_escalated_local";
                    modelUsed = localModel.modelIdentifier();
                    cost = localModel.costPerRequest();
                } catch (ModelUnavailableException e) {
                    log.warn("Local model unavailable for escalated trivial, falling back to frontier: {}", e.getMessage());
                    try {
                        answer = frontierModel.generate(optimized.text());
                        route = "rule_based_escalated_frontier";
                        modelUsed = frontierModel.modelIdentifier();
                        cost = frontierModel.costPerRequest();
                    } catch (Exception fe) {
                        log.error("Frontier model also failed: {}", fe.getMessage());
                        answer = "Both local and frontier models are unavailable. Please try again later.";
                        route = "error";
                        modelUsed = null;
                        cost = 0.0;
                    }
                }
            }
        } else if ("easy".equals(complexity.bucket())) {
            try {
                answer = localModel.generate(optimized.text());
                route = "local_model";
                modelUsed = localModel.modelIdentifier();
                cost = localModel.costPerRequest();
            } catch (ModelUnavailableException e) {
                // graceful degradation: local model cold/unavailable -> fall back to frontier
                log.warn("Local model unavailable, falling back to frontier: {}", e.getMessage());
                try {
                    answer = frontierModel.generate(optimized.text());
                    route = "local_model_fallback_frontier";
                    modelUsed = frontierModel.modelIdentifier();
                    cost = frontierModel.costPerRequest();
                } catch (Exception fe) {
                    log.error("Frontier model also failed: {}", fe.getMessage());
                    answer = "Both local and frontier models are unavailable. Please try again later.";
                    route = "error";
                    modelUsed = null;
                    cost = 0.0;
                }
            }
        } else {
            try {
                answer = frontierModel.generate(optimized.text());
                route = "frontier_model";
                modelUsed = frontierModel.modelIdentifier();
                cost = frontierModel.costPerRequest();
            } catch (Exception e) {
                log.error("Frontier model failed for hard query: {}", e.getMessage());
                answer = "The frontier model is currently unavailable. Please check your API key and try again.";
                route = "error";
                modelUsed = null;
                cost = 0.0;
            }
        }

        // Don't cache error responses
        if (!"error".equals(route)) {
            try {
                cacheService.store(optimized.text(), answer);
            } catch (Exception e) {
                log.warn("Failed to store in cache (non-fatal): {}", e.getMessage());
            }
        }

        return finish(request, traceId, route, modelUsed, start, cost, null, answer);
    }

    private QueryResponse finish(QueryRequest request, String traceId, String route, String modelUsed,
                                  long start, double cost, Double similarity, String answer) {
        long latency = System.currentTimeMillis() - start;

        // Fire-and-forget: publishing this event returns immediately. The actual
        // Postgres write happens on the "logExecutor" thread pool via @Async,
        // so it can NEVER add latency to the response below.
        eventPublisher.publishEvent(new RequestLogEvent(
            UUID.randomUUID(), traceId, request.query(), route, modelUsed,
            similarity != null, similarity, latency, cost));

        return new QueryResponse(answer, route, modelUsed, latency, cost, similarity, traceId);
    }
}

