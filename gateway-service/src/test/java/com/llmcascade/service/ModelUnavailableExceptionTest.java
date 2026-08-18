package com.llmcascade.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for {@link ModelUnavailableException}.
 * <p>
 * This exception is the contract between LocalModelProvider and RouterService
 * for graceful degradation. If it stopped extending RuntimeException (or if
 * someone accidentally changed it to a checked exception), the fallback path
 * in RouterService would break silently.
 */
class ModelUnavailableExceptionTest {

    @Test
    @DisplayName("exception is a RuntimeException (unchecked â€” caught by RouterService)")
    void isRuntimeException() {
        ModelUnavailableException ex = new ModelUnavailableException(
            "cold start", new RuntimeException("timeout"));

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("exception preserves message and cause for debugging")
    void preservesMessageAndCause() {
        Throwable cause = new RuntimeException("connection refused");
        ModelUnavailableException ex = new ModelUnavailableException("model cold", cause);

        assertThat(ex.getMessage()).isEqualTo("model cold");
        assertThat(ex.getCause()).isSameAs(cause);
    }
}

