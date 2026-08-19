package com.llmcascade.service;

import com.llmcascade.dto.QueryRequest;
import com.llmcascade.dto.QueryResponse;
import com.llmcascade.event.RequestLogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RouterService} — the core orchestration logic of the gateway.
 * <p>
 * These tests verify every branching path through the routing pipeline:
 * prompt-injection rejection, cache hits, trivial/easy/hard classification,
 * local-model fallback to frontier on timeout, and correct event publishing.
 * <p>
 * All external dependencies (optimizer, cache, classifier, model providers,
 * event publisher) are mocked so these tests run in milliseconds with zero
 * infrastructure.
 */
@ExtendWith(MockitoExtension.class)
class RouterServiceTest {

    @Mock private PromptOptimizerClient optimizerClient;
    @Mock private CacheService cacheService;
    @Mock private ClassifierClient classifierClient;
    @Mock private RuleBasedAnswerService ruleBasedAnswerService;
    @Mock private LocalModelProvider localModel;
    @Mock private FrontierModelProvider frontierModel;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RouterService routerService;

    private static final String TRACE_ID = "test-trace-id-1234";
    private static final QueryRequest SAMPLE_REQUEST = new QueryRequest("test query", "user-1", false);

    @BeforeEach
    void setUp() {
        routerService = new RouterService(
            optimizerClient, cacheService, classifierClient,
            ruleBasedAnswerService, localModel, frontierModel, eventPublisher
        );
    }

    // ──────────────────────────────────────────────────────────────────
    // 1. Prompt-injection rejection path
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Prompt-injection rejection")
    class InjectionRejection {

        @Test
        @DisplayName("rejected query returns 'rejected' route with no model call")
        void rejectedQuery_returnsRejectedRoute() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("", true));

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.route()).isEqualTo("rejected");
            assertThat(response.modelUsed()).isNull();
            assertThat(response.estimatedCostUsd()).isEqualTo(0.0);
            assertThat(response.answer()).isEqualTo("This request was flagged and cannot be processed.");

            // No downstream calls should have happened
            verifyNoInteractions(cacheService, classifierClient, localModel, frontierModel);
        }

        @Test
        @DisplayName("rejected query still publishes a log event")
        void rejectedQuery_stillPublishesEvent() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("", true));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            ArgumentCaptor<RequestLogEvent> captor = ArgumentCaptor.forClass(RequestLogEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            RequestLogEvent event = captor.getValue();
            assertThat(event.route()).isEqualTo("rejected");
            assertThat(event.traceId()).isEqualTo(TRACE_ID);
            assertThat(event.query()).isEqualTo("test query");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 2. Cache-hit path
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Semantic cache hit")
    class CacheHit {

        @BeforeEach
        void allowOptimizer() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("optimized query", false));
        }

        @Test
        @DisplayName("cache hit returns cached answer with zero cost")
        void cacheHit_returnsCachedAnswer() {
            when(cacheService.lookup("optimized query"))
                .thenReturn(Optional.of(new CacheService.CacheHit("cached answer", 0.95)));

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.route()).isEqualTo("cache_hit");
            assertThat(response.answer()).isEqualTo("cached answer");
            assertThat(response.modelUsed()).isNull();
            assertThat(response.estimatedCostUsd()).isEqualTo(0.0);
            assertThat(response.cacheSimilarity()).isEqualTo(0.95);
        }

        @Test
        @DisplayName("cache hit skips classifier and model calls entirely")
        void cacheHit_skipsDownstreamCalls() {
            when(cacheService.lookup("optimized query"))
                .thenReturn(Optional.of(new CacheService.CacheHit("cached", 0.95)));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            verifyNoInteractions(classifierClient, localModel, frontierModel);
        }

        @Test
        @DisplayName("cache hit does NOT store the answer again (no double-write)")
        void cacheHit_doesNotReStore() {
            when(cacheService.lookup("optimized query"))
                .thenReturn(Optional.of(new CacheService.CacheHit("cached", 0.95)));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            verify(cacheService, never()).store(anyString(), anyString());
        }

        @Test
        @DisplayName("cache hit event has cacheHit=true with similarity score")
        void cacheHit_eventHasCorrectFields() {
            when(cacheService.lookup("optimized query"))
                .thenReturn(Optional.of(new CacheService.CacheHit("cached", 0.97)));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            ArgumentCaptor<RequestLogEvent> captor = ArgumentCaptor.forClass(RequestLogEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            RequestLogEvent event = captor.getValue();
            assertThat(event.cacheHit()).isTrue();
            assertThat(event.cacheSimilarity()).isEqualTo(0.97);
            assertThat(event.route()).isEqualTo("cache_hit");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 3. Cache-miss → classification → routing
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cache miss → routing")
    class CacheMissRouting {

        @BeforeEach
        void allowOptimizerAndCacheMiss() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("optimized query", false));
            when(cacheService.lookup(anyString()))
                .thenReturn(Optional.empty());
        }

        // ── Trivial bucket ──

        @Test
        @DisplayName("trivial complexity + rule matched → rule_based route, zero cost, no model call")
        void trivialComplexity_usesRuleBased() {
            when(classifierClient.classify("optimized query"))
                .thenReturn(new ClassifierClient.Complexity("trivial", 0.95, 0.0, "embedding"));
            when(ruleBasedAnswerService.tryAnswer("optimized query"))
                .thenReturn(new RuleBasedAnswerService.Result(true, "Hello! How can I help you today?"));

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.route()).isEqualTo("rule_based");
            assertThat(response.modelUsed()).isNull();
            assertThat(response.estimatedCostUsd()).isEqualTo(0.0);
            assertThat(response.answer()).isEqualTo("Hello! How can I help you today?");
            verifyNoInteractions(localModel, frontierModel);
        }

        @Test
        @DisplayName("trivial answer still gets cached for future hits")
        void trivialComplexity_storesInCache() {
            when(classifierClient.classify("optimized query"))
                .thenReturn(new ClassifierClient.Complexity("trivial", 0.95, 0.0, "embedding"));
            when(ruleBasedAnswerService.tryAnswer("optimized query"))
                .thenReturn(new RuleBasedAnswerService.Result(true, "Hello!"));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            verify(cacheService).store(eq("optimized query"), eq("Hello!"));
        }

        // ── Easy bucket → local model ──

        @Test
        @DisplayName("easy complexity → local_model route with qwen2.5:1.5b")
        void easyComplexity_usesLocalModel() {
            when(classifierClient.classify("optimized query"))
                .thenReturn(new ClassifierClient.Complexity("easy", 0.85, 0.0, "embedding"));
            when(localModel.generate("optimized query")).thenReturn(new GenerationResult("local answer", 100, 100));
            when(localModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.0001);
            when(localModel.modelIdentifier()).thenReturn("qwen2.5:1.5b");

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.route()).isEqualTo("local_model");
            assertThat(response.modelUsed()).isEqualTo("qwen2.5:1.5b");
            assertThat(response.answer()).isEqualTo("local answer");
            assertThat(response.estimatedCostUsd()).isEqualTo(0.0001);
            verifyNoInteractions(frontierModel);
        }

        // ── Easy bucket → local model cold/timeout → fallback to frontier ──

        @Test
        @DisplayName("easy complexity + local timeout → falls back to frontier with fallback route")
        void easyComplexity_localTimeout_fallsBackToFrontier() {
            when(classifierClient.classify("optimized query"))
                .thenReturn(new ClassifierClient.Complexity("easy", 0.85, 0.0, "embedding"));
            when(localModel.generate("optimized query"))
                .thenThrow(new ModelUnavailableException("cold start", new RuntimeException()));
            when(frontierModel.generate("optimized query")).thenReturn(new GenerationResult("frontier fallback answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);
            when(frontierModel.modelIdentifier()).thenReturn("gemini-3.5-flash-lite");

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.route()).isEqualTo("local_model_fallback_frontier");
            assertThat(response.modelUsed()).isEqualTo("gemini-3.5-flash-lite");
            assertThat(response.answer()).isEqualTo("frontier fallback answer");
            assertThat(response.estimatedCostUsd()).isEqualTo(0.015);
        }

        @Test
        @DisplayName("fallback route is logged separately from regular frontier route")
        void easyComplexity_fallbackRoute_isDistinctInLogs() {
            when(classifierClient.classify("optimized query"))
                .thenReturn(new ClassifierClient.Complexity("easy", 0.85, 0.0, "embedding"));
            when(localModel.generate("optimized query"))
                .thenThrow(new ModelUnavailableException("cold", new RuntimeException()));
            when(frontierModel.generate("optimized query")).thenReturn(new GenerationResult("answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            ArgumentCaptor<RequestLogEvent> captor = ArgumentCaptor.forClass(RequestLogEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            // The route must say "local_model_fallback_frontier", NOT "frontier_model",
            // so eval data can distinguish intentional frontier routing from fallback.
            assertThat(captor.getValue().route()).isEqualTo("local_model_fallback_frontier");
        }

        // ── Hard bucket → frontier model ──

        @Test
        @DisplayName("hard complexity → frontier_model route")
        void hardComplexity_usesFrontierModel() {
            when(classifierClient.classify("optimized query"))
                .thenReturn(new ClassifierClient.Complexity("hard", 0.90, 0.0, "embedding"));
            when(frontierModel.generate("optimized query")).thenReturn(new GenerationResult("frontier answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);
            when(frontierModel.modelIdentifier()).thenReturn("gemini-3.5-flash-lite");

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.route()).isEqualTo("frontier_model");
            assertThat(response.modelUsed()).isEqualTo("gemini-3.5-flash-lite");
            assertThat(response.answer()).isEqualTo("frontier answer");
            assertThat(response.estimatedCostUsd()).isEqualTo(0.015);
            verifyNoInteractions(localModel);
        }

        // ── Unknown/unexpected bucket → defaults to frontier (hard path) ──

        @Test
        @DisplayName("unknown complexity bucket defaults to frontier (fail-safe)")
        void unknownBucket_defaultsToFrontier() {
            when(classifierClient.classify("optimized query"))
                .thenReturn(new ClassifierClient.Complexity("unknown_bucket", 0.5, 0.0, "embedding"));
            when(frontierModel.generate("optimized query")).thenReturn(new GenerationResult("frontier answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);
            when(frontierModel.modelIdentifier()).thenReturn("gemini-3.5-flash-lite");

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            // Any bucket that isn't "trivial" or "easy" should fall through to frontier
            assertThat(response.route()).isEqualTo("frontier_model");
            assertThat(response.modelUsed()).isEqualTo("gemini-3.5-flash-lite");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 4. Event publishing (observability contract)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Event publishing")
    class EventPublishing {

        @Test
        @DisplayName("every request publishes exactly one log event")
        void everyRequest_publishesOneEvent() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("q", false));
            when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
            when(classifierClient.classify(anyString()))
                .thenReturn(new ClassifierClient.Complexity("hard", 0.9, 0.0, "embedding"));
            when(frontierModel.generate(anyString())).thenReturn(new GenerationResult("answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            verify(eventPublisher, times(1)).publishEvent(any(RequestLogEvent.class));
        }

        @Test
        @DisplayName("event contains correct trace ID for distributed tracing")
        void event_containsTraceId() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("q", false));
            when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
            when(classifierClient.classify(anyString())).thenReturn(new ClassifierClient.Complexity("hard", 0.9, 0.0, "embedding"));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            ArgumentCaptor<RequestLogEvent> captor = ArgumentCaptor.forClass(RequestLogEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            assertThat(captor.getValue().traceId()).isEqualTo(TRACE_ID);
        }

        @Test
        @DisplayName("event captures original query, not the optimized one")
        void event_capturesOriginalQuery() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("optimized", false));
            when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
            when(classifierClient.classify(anyString())).thenReturn(new ClassifierClient.Complexity("hard", 0.9, 0.0, "embedding"));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            ArgumentCaptor<RequestLogEvent> captor = ArgumentCaptor.forClass(RequestLogEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            // The log should store the original query for debugging/eval,
            // not the optimized version
            assertThat(captor.getValue().query()).isEqualTo("test query");
        }

        @Test
        @DisplayName("event has non-null UUID")
        void event_hasNonNullId() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("q", false));
            when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
            when(classifierClient.classify(anyString())).thenReturn(new ClassifierClient.Complexity("hard", 0.9, 0.0, "embedding"));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            ArgumentCaptor<RequestLogEvent> captor = ArgumentCaptor.forClass(RequestLogEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().id()).isNotNull();
        }

        @Test
        @DisplayName("cache-miss event has cacheHit=false with null similarity")
        void cacheMiss_eventHasCorrectCacheFields() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("q", false));
            when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
            when(classifierClient.classify(anyString()))
                .thenReturn(new ClassifierClient.Complexity("hard", 0.9, 0.0, "embedding"));
            when(frontierModel.generate(anyString())).thenReturn(new GenerationResult("answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            ArgumentCaptor<RequestLogEvent> captor = ArgumentCaptor.forClass(RequestLogEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            assertThat(captor.getValue().cacheHit()).isFalse();
            assertThat(captor.getValue().cacheSimilarity()).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 5. Response contract (API shape)
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Response contract")
    class ResponseContract {

        @Test
        @DisplayName("response includes trace ID for client-side correlation")
        void response_includesTraceId() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("", true));

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.traceId()).isEqualTo(TRACE_ID);
        }

        @Test
        @DisplayName("response latency is non-negative")
        void response_latencyIsNonNegative() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("q", false));
            when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
            when(classifierClient.classify(anyString())).thenReturn(new ClassifierClient.Complexity("hard", 0.9, 0.0, "embedding"));
            

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.latencyMs()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("cache hit response has null cacheSimilarity for non-cache routes")
        void nonCacheRoute_hasSimilarityFieldCorrectlySet() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("q", false));
            when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
            when(classifierClient.classify(anyString()))
                .thenReturn(new ClassifierClient.Complexity("hard", 0.9, 0.0, "embedding"));
            when(frontierModel.generate(anyString())).thenReturn(new GenerationResult("answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);

            QueryResponse response = routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            assertThat(response.cacheSimilarity()).isNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 6. Cache store behavior
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Cache storage")
    class CacheStorage {

        @BeforeEach
        void standardSetup() {
            when(optimizerClient.optimize(anyString()))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("optimized", false));
            when(cacheService.lookup(anyString())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("non-cached answers get stored in cache for future hits")
        void cacheMiss_storesResult() {
            when(classifierClient.classify("optimized"))
                .thenReturn(new ClassifierClient.Complexity("hard", 0.9, 0.0, "embedding"));
            when(frontierModel.generate("optimized")).thenReturn(new GenerationResult("frontier answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            verify(cacheService).store("optimized", "frontier answer");
        }

        @Test
        @DisplayName("fallback answers also get cached")
        void fallbackAnswer_alsoGetsCached() {
            when(classifierClient.classify("optimized"))
                .thenReturn(new ClassifierClient.Complexity("easy", 0.85, 0.0, "embedding"));
            when(localModel.generate("optimized"))
                .thenThrow(new ModelUnavailableException("cold", new RuntimeException()));
            when(frontierModel.generate("optimized")).thenReturn(new GenerationResult("fallback answer", 100, 100));
            when(frontierModel.computeCost(org.mockito.ArgumentMatchers.any())).thenReturn(0.015);

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            verify(cacheService).store("optimized", "fallback answer");
        }

        @Test
        @DisplayName("cache stores the optimized query, not the raw one")
        void cacheStore_usesOptimizedQuery() {
            when(classifierClient.classify("optimized"))
                .thenReturn(new ClassifierClient.Complexity("trivial", 0.9, 0.0, "embedding"));
            when(ruleBasedAnswerService.tryAnswer("optimized"))
                .thenReturn(new RuleBasedAnswerService.Result(true, "mocked answer"));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            // Cache key should be the optimized query for better hit rates
            ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
            verify(cacheService).store(queryCaptor.capture(), anyString());
            assertThat(queryCaptor.getValue()).isEqualTo("optimized");
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // 7. Pipeline ordering guarantees
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Pipeline ordering")
    class PipelineOrdering {

        @Test
        @DisplayName("optimizer runs before cache lookup (cache uses optimized text)")
        void optimizer_runsBeforeCacheLookup() {
            when(optimizerClient.optimize("test query"))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("cleaned query", false));
            when(cacheService.lookup("cleaned query"))
                .thenReturn(Optional.of(new CacheService.CacheHit("cached", 0.95)));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            // Verify that cache was queried with the OPTIMIZED text, not the raw text
            verify(cacheService).lookup("cleaned query");
            verify(cacheService, never()).lookup("test query");
        }

        @Test
        @DisplayName("classifier uses optimized text, not raw query")
        void classifier_usesOptimizedText() {
            when(optimizerClient.optimize("test query"))
                .thenReturn(new PromptOptimizerClient.OptimizedQuery("cleaned query", false));
            when(cacheService.lookup("cleaned query")).thenReturn(Optional.empty());
            when(classifierClient.classify("cleaned query"))
                .thenReturn(new ClassifierClient.Complexity("trivial", 0.9, 0.0, "embedding"));
            when(ruleBasedAnswerService.tryAnswer("cleaned query"))
                .thenReturn(new RuleBasedAnswerService.Result(true, "mocked answer"));

            routerService.handle(SAMPLE_REQUEST, TRACE_ID);

            verify(classifierClient).classify("cleaned query");
        }
    }
}










