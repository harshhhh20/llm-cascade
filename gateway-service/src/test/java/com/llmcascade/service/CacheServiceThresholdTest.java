package com.llmcascade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link CacheService}'s similarity threshold logic.
 * <p>
 * The 0.92 cosine-similarity threshold is the core business rule that decides
 * whether a cached answer is "close enough" to reuse. Getting this wrong means
 * either serving stale/wrong answers (threshold too low) or never hitting the
 * cache at all (threshold too high). These tests pin the boundary behavior so
 * a threshold change can't silently flip cache behavior.
 * <p>
 * Uses the package-private constructor to inject a mocked JedisPooled,
 * avoiding any Redis infrastructure dependency.
 */
@ExtendWith(MockitoExtension.class)
class CacheServiceThresholdTest {

    @Mock private JedisPooled jedis;
    @Mock private EmbeddingClient embeddingClient;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new CacheService(jedis, embeddingClient);
        // Embedding client returns a dummy vector for any input â€”
        // the actual embedding values don't matter since we're mocking ftSearch.
        when(embeddingClient.embed(anyString())).thenReturn(new float[384]);
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Helper: build a mocked SearchResult with a specific cosine distance
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Redis returns cosine *distance* (0 = identical, 1 = orthogonal).
     * CacheService converts: similarity = 1 - distance.
     * So distance 0.05 â†’ similarity 0.95, distance 0.09 â†’ similarity 0.91.
     */
    private void stubSearchWithDistance(double distance, String answer) {
        Document doc = mock(Document.class);
        when(doc.getString("score")).thenReturn(String.valueOf(distance));
        org.mockito.Mockito.lenient().when(doc.getString("answer")).thenReturn(answer);

        SearchResult result = mock(SearchResult.class);
        when(result.getTotalResults()).thenReturn(1L);
        when(result.getDocuments()).thenReturn(List.of(doc));

        when(jedis.ftSearch(eq("query_cache_idx"), any(Query.class))).thenReturn(result);
    }

    private void stubEmptySearch() {
        SearchResult result = mock(SearchResult.class);
        when(result.getTotalResults()).thenReturn(0L);

        when(jedis.ftSearch(eq("query_cache_idx"), any(Query.class))).thenReturn(result);
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Threshold boundary tests
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    @DisplayName("Similarity threshold boundary (0.92)")
    class ThresholdBoundary {

        @Test
        @DisplayName("distance 0.05 â†’ similarity 0.95 â†’ cache HIT (well above threshold)")
        void wellAboveThreshold_isCacheHit() {
            stubSearchWithDistance(0.05, "cached answer");

            Optional<CacheService.CacheHit> result = cacheService.lookup("test query");

            assertThat(result).isPresent();
            assertThat(result.get().answer()).isEqualTo("cached answer");
            assertThat(result.get().similarity()).isCloseTo(0.95, within(0.001));
        }

        @Test
        @DisplayName("distance 0.08 â†’ similarity 0.92 â†’ cache HIT (exactly at threshold)")
        void exactlyAtThreshold_isCacheHit() {
            // similarity < 0.92 returns empty, so similarity == 0.92 should be a hit
            stubSearchWithDistance(0.08, "boundary answer");

            Optional<CacheService.CacheHit> result = cacheService.lookup("test query");

            assertThat(result).isPresent();
            assertThat(result.get().similarity()).isCloseTo(0.92, within(0.001));
        }

        @Test
        @DisplayName("distance 0.081 â†’ similarity 0.919 â†’ cache MISS (just below threshold)")
        void justBelowThreshold_isCacheMiss() {
            stubSearchWithDistance(0.081, "should not see this");

            Optional<CacheService.CacheHit> result = cacheService.lookup("test query");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("distance 0.50 â†’ similarity 0.50 â†’ cache MISS (far below threshold)")
        void farBelowThreshold_isCacheMiss() {
            stubSearchWithDistance(0.50, "definitely not this");

            Optional<CacheService.CacheHit> result = cacheService.lookup("test query");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("distance 0.0 â†’ similarity 1.0 â†’ cache HIT (exact match)")
        void perfectMatch_isCacheHit() {
            stubSearchWithDistance(0.0, "exact match answer");

            Optional<CacheService.CacheHit> result = cacheService.lookup("test query");

            assertThat(result).isPresent();
            assertThat(result.get().similarity()).isCloseTo(1.0, within(0.001));
        }
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Empty index / no results
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    @DisplayName("Empty search results")
    class EmptyResults {

        @Test
        @DisplayName("empty index (0 total results) â†’ cache MISS, no Document access")
        void emptyIndex_isCacheMiss() {
            stubEmptySearch();

            Optional<CacheService.CacheHit> result = cacheService.lookup("test query");

            assertThat(result).isEmpty();
        }
    }

    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // Store behavior
    // â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    @DisplayName("Cache store")
    class StoreTests {

        @Test
        @DisplayName("store() embeds the query and writes to Redis with correct key prefix")
        void store_writesToRedisWithPrefix() {
            cacheService.store("test query", "the answer");

            // Verify hset was called with a key starting with "cache:"
            verify(jedis).hset(
                argThat((byte[] key) -> new String(key).startsWith("cache:")),
                any()
            );
        }

        @Test
        @DisplayName("store() calls embedding client for the query text")
        void store_embedsTheQuery() {
            cacheService.store("specific query", "answer");

            verify(embeddingClient).embed("specific query");
        }
    }
}



