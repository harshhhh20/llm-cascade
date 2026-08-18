package com.llmcascade.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link TraceIdFilter}.
 * <p>
 * Validates that every request gets a unique trace ID that:
 * - appears in the response header for client-side correlation
 * - is set as a request attribute for controller access
 * - is placed in MDC for structured logging
 * - is cleaned up after the request completes (no leaking between requests)
 */
@ExtendWith(MockitoExtension.class)
class TraceIdFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    @DisplayName("filter sets X-Trace-Id response header with valid UUID")
    void setsResponseHeader() throws Exception {
        filter.doFilter(request, response, chain);

        ArgumentCaptor<String> headerValue = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq("X-Trace-Id"), headerValue.capture());

        // Should be a valid UUID format
        assertThatCode(() -> java.util.UUID.fromString(headerValue.getValue()))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("filter sets traceId request attribute for downstream access")
    void setsRequestAttribute() throws Exception {
        filter.doFilter(request, response, chain);

        ArgumentCaptor<String> attrValue = ArgumentCaptor.forClass(String.class);
        verify(request).setAttribute(eq("traceId"), attrValue.capture());
        assertThat(attrValue.getValue()).isNotBlank();
    }

    @Test
    @DisplayName("filter calls the next filter in the chain")
    void chainsToNextFilter() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("MDC is cleared after request completes (no cross-request leaking)")
    void mdcClearedAfterRequest() throws Exception {
        filter.doFilter(request, response, chain);

        // MDC should be clean after doFilter returns
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("MDC is cleared even if downstream filter throws an exception")
    void mdcClearedOnException() throws Exception {
        doThrow(new RuntimeException("downstream error"))
            .when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
            .isInstanceOf(RuntimeException.class);

        // MDC must still be cleaned up â€” a leak here means wrong trace IDs
        // appear in log lines for subsequent requests on this thread
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("response header and request attribute contain the same trace ID")
    void headerAndAttribute_match() throws Exception {
        filter.doFilter(request, response, chain);

        ArgumentCaptor<String> headerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> attrCaptor = ArgumentCaptor.forClass(String.class);

        verify(response).setHeader(eq("X-Trace-Id"), headerCaptor.capture());
        verify(request).setAttribute(eq("traceId"), attrCaptor.capture());

        assertThat(headerCaptor.getValue()).isEqualTo(attrCaptor.getValue());
    }
}

