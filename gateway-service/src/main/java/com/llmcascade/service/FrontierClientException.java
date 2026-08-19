package com.llmcascade.service;

// 4xx failures that aren't rate limiting — bad request, auth failure, etc.
// Deliberately NOT retried: retrying a malformed request or an invalid API
// key just delays the same failure and wastes the retry budget. Configured
// as an ignored exception in resilience4j.retry so it fails fast instead.
public class FrontierClientException extends RuntimeException {
    public FrontierClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
