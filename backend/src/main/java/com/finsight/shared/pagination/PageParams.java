package com.finsight.shared.pagination;

import com.finsight.shared.exception.BadRequestException;
import org.springframework.data.domain.PageRequest;

/**
 * Validates paging query parameters for list endpoints (T-60, Q-5).
 *
 * <p>Page index must be {@code >= 0}; size must be {@code >= 1} and is capped at {@value #MAX_SIZE}.
 * Sorting is fixed by each repository query, so none is set here.
 */
public final class PageParams {

    /** Default page size for list endpoints. */
    public static final int DEFAULT_SIZE = 20;

    /** Largest page size; larger requests are capped. */
    public static final int MAX_SIZE = 100;

    private PageParams() {}

    /**
     * Builds a validated, capped page request.
     *
     * @param page zero-based page index
     * @param size requested page size
     * @return the page request
     * @throws BadRequestException for a negative page or a size below 1 (400)
     */
    public static PageRequest of(int page, int size) {
        if (page < 0) {
            throw new BadRequestException("page must not be negative");
        }
        if (size < 1) {
            throw new BadRequestException("size must be at least 1");
        }
        return PageRequest.of(page, Math.min(size, MAX_SIZE));
    }
}
