package com.interviewprep.orders.springapp.controller;

import com.interviewprep.orders.springapp.dto.OrderRequest;
import com.interviewprep.orders.springapp.dto.OrderResponse;
import com.interviewprep.orders.springapp.dto.OrderStatusUpdateRequest;
import com.interviewprep.orders.springapp.entity.OrderStatus;
import com.interviewprep.orders.springapp.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The order-placement + order-lifecycle API — the module's most complete
 * example, tying together validation, the service-layer @Transactional
 * story, filtering, pagination, and the custom exception -> HTTP status
 * mappings in {@code GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/orders")
@Tag(name = "Orders", description = "Place and manage orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * Places an order. {@code @Valid} triggers Bean Validation on
     * {@code OrderRequest} (see that DTO's Javadoc on WHY {@code @Valid}
     * must ALSO appear on its nested {@code lines} list). On success,
     * returns 201 Created with the persisted order (including its
     * generated id, order number, and computed total). On insufficient
     * stock, {@code OrderService.placeOrder} lets
     * {@code InsufficientStockException} propagate — this method has no
     * try/catch at all, because {@code GlobalExceptionHandler} is where
     * that translation to HTTP 409 happens (see that class).
     */
    @PostMapping
    @Operation(summary = "Place a new order",
            description = "Reserves stock for every line atomically — either the whole order succeeds "
                    + "or none of it does (see OrderService.placeOrder's Javadoc for how @Transactional "
                    + "provides this without manual rollback code).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order placed"),
            @ApiResponse(responseCode = "400", description = "Validation failed (malformed request)"),
            @ApiResponse(responseCode = "404", description = "Customer or product not found"),
            @ApiResponse(responseCode = "409", description = "Insufficient stock for one or more lines")
    })
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody OrderRequest request) {
        OrderResponse response = orderService.placeOrder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order by id (lines + product details eager-loaded via JOIN FETCH)")
    @ApiResponse(responseCode = "404", description = "Order not found")
    public ResponseEntity<OrderResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getById(id));
    }

    /**
     * FILTERING + PAGINATION + SORTING together: {@code status} is an
     * optional filter (an enum bound directly from the query string by
     * Spring's built-in {@code Converter<String, Enum>} — {@code
     * ?status=PENDING} just works with no extra code), {@code
     * customerEmail} is a mutually-exclusive alternate filter, and
     * {@code Pageable} handles page/size/sort as in
     * {@code ProductController.search}. Real APIs often need to combine
     * MANY optional filters like this — beyond two or three, consider
     * Spring Data {@code Specification}/Querydsl (mentioned in
     * EXPLANATION.md) instead of an ever-growing parameter list with
     * branching logic like this method's.
     */
    @GetMapping
    @Operation(summary = "List orders, optionally filtered by status or customer email (paginated)")
    public ResponseEntity<Page<OrderResponse>> list(
            @RequestParam(required = false) @Parameter(description = "Filter by order status") OrderStatus status,
            @RequestParam(required = false) @Parameter(description = "Filter by a substring of the customer's email")
            String customerEmail,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        Page<OrderResponse> page = (customerEmail != null && !customerEmail.isBlank())
                ? orderService.searchByCustomerEmail(customerEmail, pageable)
                : orderService.listByStatus(status, pageable);
        return ResponseEntity.ok(page);
    }

    /**
     * {@code PATCH}, not {@code PUT}: this endpoint changes ONE field
     * (status) via an explicit business operation (a legal state
     * transition), not a full replacement of the order resource — the
     * correct HTTP-semantics distinction between PUT (replace the whole
     * resource representation) and PATCH (apply a partial modification).
     * An illegal transition (e.g. DELIVERED -> PENDING) throws
     * {@code InvalidOrderStateException}, mapped to 409 by
     * {@code GlobalExceptionHandler}; a concurrent conflicting transition
     * throws (via {@code @Version}) an optimistic-lock exception, also
     * mapped to 409 — same HTTP status, different {@code message} bodies,
     * both structurally the same {@code ApiError} shape.
     */
    @PatchMapping("/{id}/status")
    @Operation(summary = "Transition an order to a new status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated"),
            @ApiResponse(responseCode = "404", description = "Order not found"),
            @ApiResponse(responseCode = "409", description = "Illegal transition or concurrent modification")
    })
    public ResponseEntity<OrderResponse> updateStatus(@PathVariable Long id,
                                                        @Valid @RequestBody OrderStatusUpdateRequest request) {
        return ResponseEntity.ok(orderService.updateStatus(id, request.status()));
    }
}
