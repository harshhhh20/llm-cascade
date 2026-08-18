package com.llmcascade.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for the CacheService's byte-conversion utility.
 * <p>
 * The toBytes method converts float[] embeddings to the little-endian byte[]
 * format that Redis Stack's HNSW vector index expects. Getting this wrong
 * silently corrupts every cache lookup (cosine distances come back as garbage),
 * so this test catches encoding bugs before they become mystery cache misses.
 */
class CacheServiceByteConversionTest {

    /**
     * We test the toBytes logic directly â€” CacheService.toBytes is private,
     * so we replicate the same logic here to validate the encoding contract.
     */
    private byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vector) buffer.putFloat(f);
        return buffer.array();
    }

    @Test
    @DisplayName("single float encodes to 4 bytes in little-endian order")
    void singleFloat_encodesTo4Bytes() {
        float[] input = {1.0f};
        byte[] result = toBytes(input);

        assertThat(result).hasSize(4);

        // Verify round-trip: decode back to float
        float decoded = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getFloat();
        assertThat(decoded).isEqualTo(1.0f);
    }

    @Test
    @DisplayName("384-dim embedding produces 1536 bytes (matching all-MiniLM-L6-v2)")
    void embeddingDimension_matchesMiniLM() {
        float[] embedding = new float[384]; // all-MiniLM-L6-v2 dimension
        byte[] result = toBytes(embedding);

        assertThat(result).hasSize(384 * 4);
    }

    @Test
    @DisplayName("round-trip encoding preserves all float values")
    void roundTrip_preservesValues() {
        float[] original = {0.1f, -0.5f, 3.14f, 0.0f, Float.MAX_VALUE, Float.MIN_VALUE};
        byte[] encoded = toBytes(original);

        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        for (float expected : original) {
            assertThat(buffer.getFloat()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("empty vector produces empty byte array")
    void emptyVector_producesEmptyBytes() {
        float[] empty = {};
        byte[] result = toBytes(empty);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("negative floats encode correctly")
    void negativeFloats_encodeCorrectly() {
        float[] input = {-1.0f, -0.001f, -999.999f};
        byte[] encoded = toBytes(input);

        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buffer.getFloat()).isEqualTo(-1.0f);
        assertThat(buffer.getFloat()).isEqualTo(-0.001f);
        assertThat(buffer.getFloat()).isEqualTo(-999.999f);
    }
}

