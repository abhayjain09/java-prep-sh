# Cache-Aside Sequence — `ProductService.getStockBySku` / mutation paths

Two scenarios in one diagram: a cache MISS followed by a cache HIT (read
path), and a mutation that evicts the entry (write path). See
`ProductService`'s Javadoc for the full cache-aside vs. write-through
discussion this diagram illustrates.

```mermaid
sequenceDiagram
    actor Client
    participant Svc as ProductService
    participant Proxy as Spring AOP<br/>Cache Proxy
    participant Redis as Redis<br/>(productStock cache, TTL=2m)
    participant DB as PostgreSQL

    Note over Client,DB: --- SCENARIO 1: first read (cache MISS) ---
    Client->>Svc: GET /api/v1/products?... triggers getStockBySku("SKU-LAPTOP")
    Svc->>Proxy: getStockBySku("SKU-LAPTOP")
    Proxy->>Redis: GET productStock::SKU-LAPTOP
    Redis-->>Proxy: (nil — cache miss)
    Proxy->>Svc: invoke real method body
    Svc->>DB: SELECT stock_quantity FROM products WHERE sku = ?
    DB-->>Svc: 5
    Svc-->>Proxy: 5
    Proxy->>Redis: SET productStock::SKU-LAPTOP = 5, TTL 2m
    Proxy-->>Client: 5

    Note over Client,DB: --- SCENARIO 2: second read, still within TTL (cache HIT) ---
    Client->>Svc: getStockBySku("SKU-LAPTOP") again
    Svc->>Proxy: getStockBySku("SKU-LAPTOP")
    Proxy->>Redis: GET productStock::SKU-LAPTOP
    Redis-->>Proxy: 5
    Note over Proxy,Svc: real method body NEVER RUNS —<br/>zero DB queries on a hit
    Proxy-->>Client: 5

    Note over Client,DB: --- SCENARIO 3: an order is placed, stock changes (eviction) ---
    Client->>Svc: reserveStock(productId, 2)  [@CacheEvict key=#result.sku]
    Svc->>DB: SELECT ... FOR the managed Product entity
    DB-->>Svc: Product(stock=5)
    Svc->>Svc: product.decrementStock(2) -> stock=3 (dirty-checked, flushed at commit)
    Svc->>Proxy: method returns Product(sku="SKU-LAPTOP", stock=3)
    Proxy->>Redis: DEL productStock::SKU-LAPTOP
    Note over Redis: stale "5" entry removed —<br/>NOT updated to "3" directly (cache-aside, not write-through)

    Note over Client,DB: --- SCENARIO 4: next read after eviction (cache MISS again) ---
    Client->>Svc: getStockBySku("SKU-LAPTOP")
    Svc->>Proxy: getStockBySku("SKU-LAPTOP")
    Proxy->>Redis: GET productStock::SKU-LAPTOP
    Redis-->>Proxy: (nil — evicted)
    Proxy->>Svc: invoke real method body
    Svc->>DB: SELECT stock_quantity FROM products WHERE sku = ?
    DB-->>Svc: 3  (the correct, post-order value)
    Svc-->>Proxy: 3
    Proxy->>Redis: SET productStock::SKU-LAPTOP = 3, TTL 2m
    Proxy-->>Client: 3
```

## Why this is "cache-aside" and not "write-through"

The mutation path (Scenario 3) never writes the new value (`3`) into
Redis directly — it only **deletes** the stale entry. The cache is
repopulated lazily, as a side effect of the next read that happens to miss
(Scenario 4). This is the defining trait of cache-aside: the cache is
always populated *by a read*, never *by a write*. See
`ProductService`'s Javadoc and `README.md`'s Caching section for why this
module chose that over write-through, and for the TTL trade-off (why 2
minutes, not 2 seconds or 2 hours) and the "two hard things in computer
science" framing for cache invalidation.
