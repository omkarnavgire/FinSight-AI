package com.finsight.transactions.dto;

import com.finsight.transactions.domain.Category;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /api/v1/transactions/{id}/category} (FR-03-8).
 *
 * @param category the replacement category
 */
public record CategoryOverrideRequest(
        @NotNull(message = "category is required")
        Category category
) {}
