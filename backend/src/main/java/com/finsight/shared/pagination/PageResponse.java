package com.finsight.shared.pagination;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable JSON shape for every paginated list endpoint (T-60, architecture.md "Pagination").
 *
 * <p>Field names match the frontend type {@code PageResponse<T>} in
 * {@code frontend/src/shared/types/api.ts}. Spring's {@link Page} is never serialised directly,
 * so its internal structure cannot leak into or break the API contract.
 *
 * @param content       items on this page
 * @param page          zero-based page index
 * @param size          requested page size
 * @param totalElements total matching items across all pages
 * @param totalPages    total number of pages
 * @param last          {@code true} if this is the final page
 * @param <T>           item type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {
    /** Builds a response from a Spring Data page whose content is already mapped to DTOs. */
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
