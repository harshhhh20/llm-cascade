package com.llmcascade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.SearchResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.Mockito.*;

// Covers the actual business logic CacheServiceByteConversionTest doesn't:
// the similarity-threshold boundary that decides hit vs. miss. The byte
// encoding round-trip is plumbing; this is the behavior that matters.
@ExtendWith(MockitoExtension.class)
class CacheServiceLookupTest {

    @Mock private JedisPooled jedis;
    @Mock private EmbeddingClient embeddingClient;

    private CacheService cacheService;

    @BeforeEach
    void setUp() {
        cacheService = new CacheService(jedis, embeddingClient);
        when(embeddingClient.embed(anyString())).thenReturn(new float[384]);
    }

    @Test
    void similarityAboveThreshold_isTreatedAsHit() {
        SearchResult result = mock(SearchResult.class);
        Document doc = mock(Document.class);
        when(doc.getString("answer")).thenReturn("cached answer");
        when(doc.getString("score")).thenReturn("0.05"); // distance 0.05 -> similarity 0.95 (above 0.92)
        when(result.getTotalResults()).thenReturn(1L);
        when(result.getDocuments()).thenReturn(List.of(doc));
        when(jedis.ftSearch(eq("query_cache_idx"), any(redis.clients.jedis.search.Query.class))).thenReturn(result);

        Optional<CacheService.CacheHit> hit = cacheService.lookup("some query");

        assertThat(hit).isPresent();
        assertThat(hit.get().answer()).isEqualTo("cached answer");
        assertThat(hit.get().similarity()).isCloseTo(0.95, offset(0.001));
    }

    @Test
    void similarityBelowThreshold_isTreatedAsMiss() {
        SearchResult result = mock(SearchResult.class);
        Document doc = mock(Document.class);
        when(doc.getString("score")).thenReturn("0.30"); // distance 0.30 -> similarity 0.70 (below 0.92)
        when(result.getTotalResults()).thenReturn(1L);
        when(result.getDocuments()).thenReturn(List.of(doc));
        when(jedis.ftSearch(eq("query_cache_idx"), any(redis.clients.jedis.search.Query.class))).thenReturn(result);

        Optional<CacheService.CacheHit> hit = cacheService.lookup("some query");

        assertThat(hit).isEmpty();
    }

    @Test
    void similarityExactlyAtThreshold_isTreatedAsHit() {
        SearchResult result = mock(SearchResult.class);
        Document doc = mock(Document.class);
        when(doc.getString("answer")).thenReturn("boundary answer");
        when(doc.getString("score")).thenReturn("0.08"); // distance 0.08 -> similarity exactly 0.92
        when(result.getTotalResults()).thenReturn(1L);
        when(result.getDocuments()).thenReturn(List.of(doc));
        when(jedis.ftSearch(eq("query_cache_idx"), any(redis.clients.jedis.search.Query.class))).thenReturn(result);

        Optional<CacheService.CacheHit> hit = cacheService.lookup("some query");

        assertThat(hit).isPresent();
    }

    @Test
    void noResults_isTreatedAsMiss() {
        SearchResult result = mock(SearchResult.class);
        when(result.getTotalResults()).thenReturn(0L);
        when(jedis.ftSearch(eq("query_cache_idx"), any(redis.clients.jedis.search.Query.class))).thenReturn(result);

        Optional<CacheService.CacheHit> hit = cacheService.lookup("some query");

        assertThat(hit).isEmpty();
    }
}

