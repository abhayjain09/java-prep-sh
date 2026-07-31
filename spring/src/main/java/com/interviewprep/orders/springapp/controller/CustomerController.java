package com.interviewprep.orders.springapp.controller;

import com.interviewprep.orders.springapp.dto.CustomerRequest;
import com.interviewprep.orders.springapp.dto.CustomerResponse;
import com.interviewprep.orders.springapp.entity.Customer;
import com.interviewprep.orders.springapp.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * CRUD over {@code Customer}. This controller is the module's ONE HATEOAS
 * example — see {@code getById} below and spring/README.md's HATEOAS
 * section for when this pattern is (and, more often, isn't) worth the
 * complexity in a real API.
 *
 * ============================================================================
 * API VERSIONING: THIS MODULE USES PATH VERSIONING ({@code /api/v1/...}) —
 * SEE README.md FOR THE FULL COMPARISON. Summarized at the point of use:
 * ============================================================================
 * - PATH VERSIONING (used here, {@code /api/v1/customers}): the version is
 *   visible in the URL itself. Pros: trivially cacheable per-version by any
 *   HTTP cache/CDN/proxy with zero configuration (different URL = different
 *   cache entry, automatically), easy to test in a browser/curl, obvious to
 *   API consumers reading logs or documentation. Cons: technically violates
 *   the REST/HATEOAS idea that a URL should identify a RESOURCE, not a
 *   resource-plus-a-protocol-version — "the customer" arguably shouldn't
 *   have two different URLs (v1, v2) for what is conceptually one entity.
 * - HEADER VERSIONING (e.g. a custom {@code X-API-Version: 2} request
 *   header): keeps the URL stable across versions (cleaner from a pure
 *   REST-resource-identity standpoint), but is invisible in server access
 *   logs unless specifically configured to capture it, harder to
 *   test/demo casually (can't just paste a URL in a browser), and
 *   intermediate caches/proxies need explicit `Vary: X-API-Version`
 *   configuration to avoid serving the wrong version's cached response.
 * - CONTENT-NEGOTIATION VERSIONING (e.g.
 *   {@code Accept: application/vnd.interviewprep.orders-v2+json}): the
 *   "most RESTful" option per Roy Fielding's own stated preference (the
 *   URL identifies the resource; the representation's version is
 *   negotiated like any other content type) — but it's the least
 *   ergonomic in practice: awkward to explore/debug manually, and most
 *   real client tooling/HTTP libraries make custom Accept headers more
 *   friction than a query param or path segment.
 * In practice, path versioning is what you'll see most often at
 * companies with public APIs (Stripe, GitHub both use path-adjacent or
 * header schemes actually — GitHub uses an Accept header, Stripe pins an
 * account-level version — so "most common" varies by company; the honest
 * answer in an interview is: know all three, state the trade-offs, and
 * justify whichever your team already standardized on rather than
 * asserting one is objectively correct).
 */
@RestController
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "CRUD operations for customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @Operation(summary = "Create a customer")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Customer created",
                    content = @Content(schema = @Schema(implementation = CustomerResponse.class))),
            @ApiResponse(responseCode = "400", description = "Validation failed")
    })
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        Customer created = customerService.create(request);
        CustomerResponse body = CustomerResponse.from(created);
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    /**
     * THE HATEOAS EXAMPLE. HATEOAS ("Hypermedia As The Engine Of
     * Application State") means a response doesn't just return data — it
     * also returns LINKS describing what a client can legally do next
     * (here: "self" and "orders"), so a well-behaved client could in
     * theory navigate the API by following links rather than hardcoding
     * URL templates everywhere.
     *
     * HOW OFTEN THIS IS ACTUALLY USED IN PRACTICE (be honest about this in
     * an interview rather than reciting textbook HATEOAS enthusiasm):
     * INTERNAL APIs (service-to-service, or a backend serving its own
     * known frontend) very often skip HATEOAS entirely — the client and
     * server are deployed together/in lockstep, URL templates are already
     * known at compile time on the frontend, and the extra payload size +
     * server-side complexity of generating links buys little. PUBLIC APIs
     * with many independent, unknown consumers (Stripe, GitHub, PayPal)
     * use hypermedia-style links more — e.g. Stripe's list responses
     * include pagination URLs, GitHub's API includes a {@code Link} header
     * for pagination — though usually a pragmatic SUBSET of full HATEOAS
     * (mainly pagination/navigation links) rather than links describing
     * every possible state transition. Full "hypermedia-driven" API
     * design (the level this method demonstrates) is genuinely rare in
     * production; know it for interviews, reach for it in real systems
     * only when you have many decoupled clients that would actually
     * benefit from discoverability.
     */
    @GetMapping("/{id}")
    @Operation(summary = "Get a customer by id (includes HATEOAS links)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer found"),
            @ApiResponse(responseCode = "404", description = "Customer not found")
    })
    public ResponseEntity<CustomerResponse> getById(@PathVariable Long id) {
        Customer customer = customerService.getById(id);
        CustomerResponse response = CustomerResponse.from(customer);

        // Self link, built from THIS controller method via methodOn(...) —
        // if this endpoint's @RequestMapping path ever changes, this link
        // is regenerated correctly without manual string concatenation.
        response.add(linkTo(methodOn(CustomerController.class).getById(id)).withSelfRel());

        // A hand-built link to this customer's orders — OrderController's
        // filtering endpoint takes a customerEmail query param (see
        // OrderController.list), so this demonstrates a link that points
        // to a DIFFERENT resource/controller, which is the actual point of
        // hypermedia — guiding a client from one resource to a related one.
        response.add(Link.of("/api/v1/orders?customerEmail=" + customer.getEmail(), "orders"));

        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Operation(summary = "List customers (paginated)")
    public ResponseEntity<Page<CustomerResponse>> list(
            @PageableDefault(size = 20, sort = "id") Pageable pageable) {
        Page<CustomerResponse> page = customerService.list(pageable).map(CustomerResponse::from);
        return ResponseEntity.ok(page);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a customer's name/email")
    public ResponseEntity<CustomerResponse> update(@PathVariable Long id,
                                                     @Valid @RequestBody CustomerRequest request) {
        Customer updated = customerService.update(id, request);
        return ResponseEntity.ok(CustomerResponse.from(updated));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a customer")
    @ApiResponse(responseCode = "204", description = "Customer deleted")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        customerService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
