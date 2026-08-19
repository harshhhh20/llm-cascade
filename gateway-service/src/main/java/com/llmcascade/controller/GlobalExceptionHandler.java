package com.llmcascade.controller;

import com.llmcascade.filter.TraceIdFilter;
import com.llmcascade.service.FrontierClientException;
import com.llmcascade.service.FrontierUnavailableException;
import com.llmcascade.service.RateLimitedException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private record ErrorBody(String error, String category, String traceId) {}

    private String traceId(HttpServletRequest req) {
        Object t = req.getAttribute(TraceIdFilter.TRACE_ID_ATTR);
        return t != null ? t.toString() : "unknown";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .orElse("Invalid request");
        return ResponseEntity.badRequest().body(new ErrorBody(message, "validation_error", traceId(req)));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<?> handleRateLimited(RateLimitedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorBody("The model provider is rate-limiting requests. Try again shortly.",
                "frontier_rate_limited", traceId(req)));
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<?> handleCircuitOpen(CallNotPermittedException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorBody("The model provider is temporarily unavailable (circuit breaker open).",
                "frontier_circuit_open", traceId(req)));
    }

    @ExceptionHandler(FrontierUnavailableException.class)
    public ResponseEntity<?> handleFrontierUnavailable(FrontierUnavailableException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(new ErrorBody("The model provider is unavailable.", "frontier_unavailable", traceId(req)));
    }

    @ExceptionHandler(FrontierClientException.class)
    public ResponseEntity<?> handleFrontierClientError(FrontierClientException ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(new ErrorBody("The model provider rejected the request.", "frontier_client_error", traceId(req)));
    }

    // Last-resort safety net for anything not explicitly handled above —
    // still returns a structured body with a trace_id instead of a raw stack
    // trace, but the "unexpected_error" category is a signal that a new
    // failure mode showed up and probably deserves its own handler.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleUnexpected(Exception ex, HttpServletRequest req) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorBody("An unexpected error occurred.", "unexpected_error", traceId(req)));
    }
}
