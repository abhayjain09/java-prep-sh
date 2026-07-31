# Request Flow — Controller -> Service -> Repository -> DB

Traces a single `POST /api/v1/orders` request (placing an order) through
every layer this module introduces. Read alongside `EXPLANATION.md`'s
walkthrough of `OrderController.placeOrder` / `OrderService.placeOrder`.

```mermaid
sequenceDiagram
    actor Client
    participant DispatcherServlet as Spring MVC<br/>DispatcherServlet
    participant Validator as Bean Validation<br/>(@Valid)
    participant Controller as OrderController
    participant Service as OrderService<br/>(@Transactional)
    participant ProductSvc as ProductService<br/>(@Cacheable/@CacheEvict)
    participant Cache as Redis<br/>(productStock cache)
    participant Repo as OrderRepository /<br/>ProductRepository (Spring Data)
    participant DB as PostgreSQL

    Client->>DispatcherServlet: POST /api/v1/orders {customerId, lines[]}
    DispatcherServlet->>Validator: bind + validate OrderRequest
    alt validation fails
        Validator-->>Client: 400 Bad Request (via GlobalExceptionHandler)
    else validation passes
        DispatcherServlet->>Controller: placeOrder(OrderRequest)
        Controller->>Service: placeOrder(request)
        activate Service
        Note over Service: @Transactional opens a DB transaction
        Service->>Repo: customerRepository.findById(customerId)
        Repo->>DB: SELECT * FROM customers WHERE id = ?
        DB-->>Repo: customer row
        Repo-->>Service: Customer

        loop for each requested line
            Service->>ProductSvc: reserveStock(productId, quantity)
            ProductSvc->>Repo: productRepository.findById(productId)
            Repo->>DB: SELECT * FROM products WHERE id = ?
            DB-->>Repo: product row (with current stock + version)
            Repo-->>ProductSvc: Product
            alt insufficient stock
                ProductSvc-->>Service: throws InsufficientStockException
                Note over Service: @Transactional rolls back the WHOLE<br/>transaction — no manual undo needed
                Service-->>Controller: exception propagates
                Controller-->>DispatcherServlet: exception propagates
                DispatcherServlet-->>Client: 409 Conflict (GlobalExceptionHandler)
            else stock available
                ProductSvc->>ProductSvc: product.decrementStock(quantity)
                Note over ProductSvc: managed entity — Hibernate<br/>dirty-checks the change, no explicit save()
                ProductSvc-->>Cache: @CacheEvict productStock::<sku>
                ProductSvc-->>Service: Product (with price snapshot)
                Service->>Service: order.addLine(new OrderLine(product, qty, price))
            end
        end

        Service->>Repo: orderRepository.save(order)
        Repo->>DB: INSERT INTO orders ... ; INSERT INTO order_lines ...
        Note over Service,DB: COMMIT — all inserts/updates in this<br/>transaction become durable together
        deactivate Service
        Service-->>Controller: OrderResponse
        Controller-->>DispatcherServlet: 201 Created + OrderResponse JSON
        DispatcherServlet-->>Client: 201 Created
    end
```

## Key things this diagram makes concrete

- **Validation happens before the controller method body runs at all** —
  Spring's argument resolvers validate `@Valid @RequestBody OrderRequest`
  during argument binding, so an invalid request never reaches
  `OrderController.placeOrder`'s first line.
- **The whole loop over order lines is ONE database transaction.** If line 3
  of 5 fails with `InsufficientStockException`, lines 1-2's stock
  decrements are rolled back automatically — see `OrderService`'s Javadoc
  for the detailed contrast with java-basics' manual `Deque`-based rollback.
- **Cache eviction happens per-line, synchronously, as each `reserveStock`
  call returns** — not deferred to transaction commit. See
  `cache-aside-sequence.md` and `ProductService`'s Javadoc for why that's a
  deliberate, understood trade-off (a harmless extra cache miss on
  rollback) rather than an oversight.
- **Every exception path funnels through `GlobalExceptionHandler`** — no
  controller or service method in this diagram catches anything itself.
