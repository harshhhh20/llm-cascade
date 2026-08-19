package com.llmcascade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record QueryRequest(
    @NotBlank(message = "query must not be blank")
    @Size(max = 4000, message = "query must be under 4000 characters")
    String query,

    String userId,

    // eval-harness only — bypasses optimizer/cache/classifier for baseline comparison
    Boolean forceFrontier
) {}
