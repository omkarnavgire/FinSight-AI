package com.finsight.shared.exception;

/**
 * Base exception for resource-not-found and cross-user-access scenarios.
 *
 * <p>Both "resource does not exist" and "resource belongs to a different user"
 * throw this exception and map to HTTP 404 Not Found. Returning 403 on
 * cross-user access would leak the existence of the resource; 404 avoids that.
 *
 * <p>Concrete subclasses follow the naming pattern {@code <Entity>NotFoundException},
 * e.g. {@code AccountNotFoundException}, {@code BudgetNotFoundException}.
 *
 * <p>All service methods that accept an entity {@code id} MUST also accept
 * and verify the {@code userId} extracted from the Spring Security context
 * (never from a request body or path variable). The canonical repository query
 * shape is:
 *
 * <pre>{@code
 * Optional<T> findByIdAndUserId(Long id, Long userId);
 * }</pre>
 *
 * When the query returns empty — whether because the entity does not exist or
 * because it belongs to a different user — the service throws this exception.
 *
 * @see com.finsight.shared.advice.GlobalExceptionHandler
 */
public abstract class NotFoundException extends FinSightException {

    protected NotFoundException(String message) {
        super(message);
    }
}
