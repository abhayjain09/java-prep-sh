package com.interviewprep.orders.springapp.controller;

import com.interviewprep.orders.springapp.dto.ProductRequest;
import com.interviewprep.orders.springapp.dto.ProductResponse;
import com.interviewprep.orders.springapp.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * CRUD + search over {@code Product}, and this module's PAGINATION /
 * SORTING / FILTERING example.
 *
 * ============================================================================
 * PAGINATION/SORTING VIA SPRING DATA'S {@code Pageable}: how the pieces fit
 * together (worth being able to explain end-to-end, not just "I added
 * Pageable as a parameter"):
 * ============================================================================
 * 1. Spring MVC's {@code PageableHandlerMethodArgumentResolver} (auto-
 *    registered by {@code spring-boot-starter-data-jpa}/{@code -web}
 *    together) parses standard query params — {@code ?page=0&size=20&
 *    sort=name,asc&sort=price,desc} (multiple {@code sort} params compose
 *    into a multi-key sort) — into a {@code Pageable} object, with NO code
 *    in this controller needed to parse them manually.
 * 2. {@code @PageableDefault} sets what happens when a client omits some or
 *    all of those params (defaults to page 0, size 20, sorted by name here)
 *    — always set a sane default {@code size}; an unbounded/very large
 *    default page size is a common accidental-DoS-vector in APIs that skip
 *    this.
 * 3. Passing the {@code Pageable} straight to
 *    {@code productRepository.findByNameContainingIgnoreCase(filter, pageable)}
 *    lets Spring Data translate it into the DB-appropriate
 *    {@code LIMIT}/{@code OFFSET} + {@code ORDER BY} — see
 *    {@code ProductRepository}'s Javadoc for the "this is actually two
 *    queries" note (the page of data + a separate COUNT).
 * 4. The response is a {@code Page<ProductResponse>}, which Jackson
 *    serializes with {@code content}, {@code totalElements}, {@code
 *    totalPages}, {@code number} (current page), {@code size}, and sort
 *    metadata — a client can page forward by incrementing {@code page} and
 *    knows when to stop via {@code totalPages}/{@code last}.
 *
 * KNOWN CAVEAT WORTH MENTIONING IN AN INTERVIEW: returning Spring Data's
 * {@code Page}/{@code PageImpl} DIRECTLY from a controller (as done here for
 * brevity) triggers a logged warning in recent Spring Data versions because
 * {@code PageImpl}'s JSON shape isn't guaranteed stable across versions and
 * isn't a "real" contract type. Production code more often maps to a small
 * custom {@code PagedResponse<T>} record (content + page/size/totalElements/
 * totalPages fields you control) or uses Spring Data's
 * {@code PagedModel}/HATEOAS paging — trading a little boilerplate for a
 * response shape you own and can evolve independently of the Spring Data
 * Commons version, matching the same "never expose an implementation detail
 * as your API contract" principle DTOs apply to entities.
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "CRUD and stock queries for products")
@Validated // required for @Min/@Positive on plain @RequestParam method args (not @RequestBody) to be enforced
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @Operation(summary = "Create a product")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Product created"),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = productService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a product by id")
    @ApiResponse(responseCode = "404", description = "Product not found")
    public ResponseEntity<ProductResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(productService.getById(id));
    }

    /**
     * FILTERING (a {@code name} substring match, case-insensitive) combined
     * with PAGINATION/SORTING in one endpoint — the common real-world shape
     * of a "search products" endpoint. {@code name} is optional (omitted =
     * no filter, matches everything) rather than required, so this one
     * endpoint serves both "list all products" and "search by name" without
     * two separate routes.
     */
    @GetMapping
    @Operation(summary = "Search products by name (paginated, sortable)")
    public ResponseEntity<Page<ProductResponse>> search(
            @RequestParam(required = false) @Parameter(description = "Case-insensitive name substring filter")
            String name,
            @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return ResponseEntity.ok(productService.search(name, pageable));
    }

    /**
     * Demonstrates the {@code @Query}-backed low-stock JPQL repository
     * method end to end, and validates a plain {@code @RequestParam} (not a
     * {@code @RequestBody}, so {@code @Valid} alone wouldn't trigger —
     * {@code @Min} here needs the class-level {@code @Validated} above to
     * be enforced on simple parameters).
     */
    @GetMapping("/low-stock")
    @Operation(summary = "List products with stock below a threshold")
    public ResponseEntity<List<ProductResponse>> lowStock(
            @RequestParam @Min(value = 0, message = "threshold must be zero or positive") int threshold) {
        return ResponseEntity.ok(productService.lowStock(threshold));
    }

    /**
     * A simple admin-style restock endpoint — exercises
     * {@code ProductService.restock}'s {@code @CacheEvict} path outside of
     * the order-placement flow (e.g. a warehouse receiving shipment
     * confirms new stock arrived).
     */
    @PostMapping("/{sku}/restock")
    @Operation(summary = "Increase a product's stock (e.g. after a warehouse delivery)")
    public ResponseEntity<Void> restock(@PathVariable String sku,
                                         @RequestParam @Positive(message = "quantity must be positive") int quantity) {
        productService.restock(sku, quantity);
        return ResponseEntity.noContent().build();
    }
}
