package com.interviewprep.orders.springapp.exception;

/**
 * Thrown by service-layer lookups (e.g. {@code productRepository.findById(id)
 * .orElseThrow(() -> new ResourceNotFoundException(...))}) when a requested
 * resource doesn't exist. Mapped by {@code GlobalExceptionHandler} to HTTP
 * 404 Not Found.
 *
 * WHY A GENERIC ONE CLASS FOR ALL RESOURCE TYPES (not CustomerNotFoundException,
 * ProductNotFoundException, OrderNotFoundException separately): none of this
 * module's callers need to distinguish "which kind of thing was missing"
 * programmatically — they all get the same HTTP 404 treatment either way.
 * Contrast this deliberately with {@code InsufficientStockException}, which
 * DOES get its own type because it needs materially different handling (409
 * with structured sku/requested/available fields, not a generic message).
 * This is the same "don't create a custom exception type unless callers
 * need to distinguish it" principle java-basics' Javadoc makes about
 * InsufficientStockException, applied in the opposite direction here.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException forId(String resourceType, Object id) {
        return new ResourceNotFoundException("%s not found with id: %s".formatted(resourceType, id));
    }
}
