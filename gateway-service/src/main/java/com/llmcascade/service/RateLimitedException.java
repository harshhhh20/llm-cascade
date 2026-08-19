package com.llmcascade.service;

// Thrown when the frontier provider returns a rate-limit response (HTTP 429).
// Kept distinct from a generic failure so the router can log
// "frontier_rate_limited" instead of an opaque "error", and so retry logic
// can specifically target this case with backoff.
public class RateLimitedException extends RuntimeException {
    public RateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }
}
