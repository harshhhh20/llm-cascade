package com.llmcascade.service;

// Non-rate-limit frontier failures: 5xx, network issues, timeouts.
// Distinct from RateLimitedException so stats/logs show which failure mode
// is actually occurring instead of one undifferentiated "error" route.
public class FrontierUnavailableException extends RuntimeException {
    public FrontierUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
