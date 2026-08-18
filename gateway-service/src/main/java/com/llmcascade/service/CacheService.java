package com.llmcascade.service;

import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// Plain Redis has no cosine-similarity search â€” this relies on Redis Stack
// (RediSearch module) and the vector index created by redis/create-index.sh.
// JedisPooled is injected (see RedisConfig) rather than constructed here,
// specifically so this class is unit-testable with a mocked client.
@Component
public class CacheService {

    private static final double SIMILARITY_THRESHOLD = 0.92;
    private static final String INDEX_NAME = "query_cache_idx";

    private final JedisPooled jedis;
    private final EmbeddingClient embeddingClient;

    public CacheService(JedisPooled jedis, EmbeddingClient embeddingClient) {
        this.jedis = jedis;
        this.embeddingClient = embeddingClient;
    }

    public record CacheHit(String answer, double similarity) {}

    public Optional<CacheHit> lookup(String query) {
        float[] embedding = embeddingClient.embed(query);
        byte[] vectorBytes = toBytes(embedding);

        Query q = new Query("*=>[KNN 1 @embedding $vec AS score]")
            .addParam("vec", vectorBytes)
            .returnFields("answer", "score")
            .setSortBy("score", true)
            .dialect(2);

        SearchResult result = jedis.ftSearch(INDEX_NAME, q);
        if (result.getTotalResults() == 0) return Optional.empty();

        Document doc = result.getDocuments().get(0);
        double distance = Double.parseDouble(doc.getString("score"));
        double similarity = 1 - distance; // cosine distance -> similarity
        if (similarity < SIMILARITY_THRESHOLD) return Optional.empty();

        return Optional.of(new CacheHit(doc.getString("answer"), similarity));
    }

    public void store(String query, String answer) {
        float[] embedding = embeddingClient.embed(query);
        String key = "cache:" + UUID.randomUUID();

        Map<byte[], byte[]> fields = new HashMap<>();
        fields.put("query".getBytes(), query.getBytes());
        fields.put("answer".getBytes(), answer.getBytes());
        fields.put("embedding".getBytes(), toBytes(embedding));

        jedis.hset(key.getBytes(), fields);
    }

    private byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) buffer.putFloat(f);
        return buffer.array();
    }
}

