package com.finsight.auth.service;

import java.util.List;

/**
 * Read-only list of registered user IDs for background jobs (Phase 8, cash-flow scheduler).
 *
 * <p>This is the only way for another module to enumerate users. It exposes IDs only, never
 * emails, names or password hashes, and it never exposes {@code UserRepository}.
 */
public interface UserDirectory {

    /** Largest page a caller may request. */
    int MAX_PAGE_SIZE = 1000;

    /**
     * Returns the next page of user IDs after {@code lastId}, in ascending order (keyset paging).
     *
     * @param lastId exclusive lower bound; {@code 0} for the first page, then the last ID returned
     * @param limit  page size, 1 to {@value #MAX_PAGE_SIZE}
     * @return up to {@code limit} IDs; fewer than {@code limit} means this was the last page
     * @throws com.finsight.shared.exception.BadRequestException if {@code lastId < 0} or {@code limit} is out of range
     */
    List<Long> userIdsAfter(long lastId, int limit);
}
