package com.llmcascade.service;

public record GenerationResult(String text, int promptTokens, int completionTokens) {}
