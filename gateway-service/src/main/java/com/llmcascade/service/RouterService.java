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

        if (Boolean.TRUE.equals(request.forceFrontier())) {
            GenerationResult result = callFrontier(request, traceId, start, "baseline_frontier");
            return finish(request, traceId, "baseline_frontier", frontierModel.modelIdentifier(),
                start, frontierModel.computeCost(result), null, result.text(), result.promptTokens(), result.completionTokens());
        }

        PromptOptimizerClient.OptimizedQuery optimized = optimizerClient.optimize(request.query());
        if (optimized.rejected()) {
            return finish(request, traceId, "rejected", null, start, 0.0, null,
                "This request was flagged and cannot be processed.", 0, 0);
        }

        try {
            var cacheResult = cacheService.lookup(optimized.text());
            if (cacheResult.isPresent()) {
                var hit = cacheResult.get();
                return finish(request, traceId, "cache_hit", null, start, 0.0, hit.similarity(), hit.answer(), 0, 0);
            }
        } catch (Exception e) {
            log.warn("Cache lookup failed (non-fatal, skipping cache): {}", e.getMessage());
        }

        ClassifierClient.Complexity complexity;
        try {
            complexity = classifierClient.classify(optimized.text());
            log.info("Classifier routed to {} (score: {}, margin: {}, method: {})", complexity.bucket(), complexity.score(), complexity.margin(), complexity.method());
        } catch (Exception e) {
            log.warn("Classifier failed, defaulting to 'hard' bucket: {}", e.getMessage());
            complexity = new ClassifierClient.Complexity("hard", 0.0, 0.0, "error_fallback");
        }

        String answer;
        String route;
        String modelUsed;
        double cost;
        int promptTokens = 0;
        int completionTokens = 0;

        if ("trivial".equals(complexity.bucket())) {
            var ruleResult = ruleBasedAnswerService.tryAnswer(optimized.text());
            if (ruleResult.handled()) {
                answer = ruleResult.answer();
                route = "rule_based";
                modelUsed = null;
                cost = 0.0;
            } else {
                try {
                    GenerationResult result = localModel.generate(optimized.text());
                    answer = result.text();
                    route = "rule_based_escalated_local";
                    modelUsed = localModel.modelIdentifier();
                    cost = localModel.computeCost(result);
                    promptTokens = result.promptTokens();
                    completionTokens = result.completionTokens();
                } catch (ModelUnavailableException e) {
                    log.warn("Local model unavailable for escalated trivial, falling back to frontier: {}", e.getMessage());
                    GenerationResult result = callFrontier(request, traceId, start, "rule_based_escalated_frontier");
                    answer = result.text();
                    route = "rule_based_escalated_frontier";
                    modelUsed = frontierModel.modelIdentifier();
                    cost = frontierModel.computeCost(result);
                    promptTokens = result.promptTokens();
                    completionTokens = result.completionTokens();
                }
            }
        } else if ("easy".equals(complexity.bucket())) {
            try {
                GenerationResult result = localModel.generate(optimized.text());
                answer = result.text();
                route = "local_model";
                modelUsed = localModel.modelIdentifier();
                cost = localModel.computeCost(result);
                promptTokens = result.promptTokens();
                completionTokens = result.completionTokens();
            } catch (ModelUnavailableException e) {
                log.warn("Local model unavailable, falling back to frontier: {}", e.getMessage());
                GenerationResult result = callFrontier(request, traceId, start, "local_model_fallback_frontier");
                answer = result.text();
                route = "local_model_fallback_frontier";
                modelUsed = frontierModel.modelIdentifier();
                cost = frontierModel.computeCost(result);
                promptTokens = result.promptTokens();
                completionTokens = result.completionTokens();
            }
        } else {
            GenerationResult result = callFrontier(request, traceId, start, "frontier_model");
            answer = result.text();
            route = "frontier_model";
            modelUsed = frontierModel.modelIdentifier();
            cost = frontierModel.computeCost(result);
            promptTokens = result.promptTokens();
            completionTokens = result.completionTokens();
        }

        try {
            cacheService.store(optimized.text(), answer);
        } catch (Exception e) {
            log.warn("Failed to store in cache (non-fatal): {}", e.getMessage());
        }

        return finish(request, traceId, route, modelUsed, start, cost, null, answer, promptTokens, completionTokens);
    }

    private GenerationResult callFrontier(QueryRequest request, String traceId, long start, String attemptedRoute) {
        try {
            return frontierModel.generate(request.query());
        } catch (RateLimitedException e) {
            logFailure(request, traceId, start, "frontier_rate_limited");
            throw e;
        } catch (FrontierClientException e) {
            logFailure(request, traceId, start, "frontier_client_error");
            throw e;
        } catch (FrontierUnavailableException e) {
            logFailure(request, traceId, start, "frontier_unavailable");
            throw e;
        }
    }

    private void logFailure(QueryRequest request, String traceId, long start, String route) {
        long latency = System.currentTimeMillis() - start;
        eventPublisher.publishEvent(new RequestLogEvent(
            UUID.randomUUID(), traceId, request.query(), route, null,
            false, null, latency, 0.0, 0, 0));
    }

    private QueryResponse finish(QueryRequest request, String traceId, String route, String modelUsed,
                                  long start, double cost, Double similarity, String answer, int promptTokens, int completionTokens) {
        long latency = System.currentTimeMillis() - start;

        eventPublisher.publishEvent(new RequestLogEvent(
            UUID.randomUUID(), traceId, request.query(), route, modelUsed,
            similarity != null, similarity, latency, cost, promptTokens, completionTokens));

        return new QueryResponse(answer, route, modelUsed, latency, cost, similarity, traceId);
    }
}
