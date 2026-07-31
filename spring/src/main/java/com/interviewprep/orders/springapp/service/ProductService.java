package com.interviewprep.orders.springapp.service;

import com.interviewprep.orders.springapp.dto.ProductRequest;
import com.interviewprep.orders.springapp.dto.ProductResponse;
import com.interviewprep.orders.springapp.entity.Product;
import com.interviewprep.orders.springapp.exception.ResourceNotFoundException;
import com.interviewprep.orders.springapp.repository.ProductRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Product CRUD + the module's Module 8 (caching) centerpiece:
 * {@code getStockBySku} is cache-aside read-through, and every mutation
 * that changes stock evicts that cache entry.
 *
 * ============================================================================
 * CACHE-ASIDE (a.k.a. LAZY LOADING) VS. WRITE-THROUGH — WHY THIS MODULE
 * USES CACHE-ASIDE (full comparison in spring/README.md's Caching section,
 * summarized here at the point of use):
 * ============================================================================
 * - CACHE-ASIDE (used here): the application checks the cache first; on a
 *   miss, it reads from the DB and POPULATES the cache for next time
 *   (that's exactly what {@code @Cacheable} does — see {@code getStockBySku}).
 *   Writes go straight to the DB and simply EVICT the now-stale cache entry
 *   ({@code @CacheEvict}) rather than updating it, so the next read
 *   repopulates it correctly. Simple, and the cache can be wiped entirely
 *   at any time with no data-loss risk (the DB is always the source of
 *   truth) — the main risk is a brief "thundering herd" of DB reads right
 *   after a popular key is evicted or expires, all missing the cache at
 *   once.
 * - WRITE-THROUGH (not used here): every write goes through the cache,
 *   which synchronously writes to the DB and updates itself in the same
 *   operation, so the cache is never stale after a write it participated
 *   in. Better staleness guarantees, but adds write latency (every write
 *   waits on both cache and DB) and requires the caching layer to own
 *   write logic, which doesn't fit naturally with Spring's
 *   annotation-driven cache abstraction (which is fundamentally a
 *   read-cache / evict-on-write model) without a lot of custom plumbing.
 *
 * WHY CACHE INVALIDATION IS "ONE OF THE TWO HARD THINGS IN COMPUTER
 * SCIENCE" HERE SPECIFICALLY: {@code decrementStock}/{@code restock} must
 * evict {@code productStock::<sku>} in the SAME transaction as the DB write
 * that changed the real value. If eviction happened BEFORE the DB commit
 * and another request read-and-repopulated the cache from the (not yet
 * committed, but already visible to that read within the same DB
 * transaction isolation rules... or worse, if the transaction later rolled
 * back) stock value in between, the cache would now hold a value that was
 * never actually true. Spring's default {@code @CacheEvict} runs AFTER the
 * annotated method returns successfully (not tied to transaction commit by
 * default) — for strict correctness under concurrent writes, pairing this
 * with {@code @Transactional} at the calling site (see {@code OrderService})
 * so a rollback also effectively "undoes" the eviction's timing relative to
 * the failed write is important to reason about explicitly, not assume.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product product = new Product(request.sku(), request.name(), request.price(),
                request.initialStockQuantity());
        return ProductResponse.from(productRepository.save(product));
    }

    @Transactional(readOnly = true)
    public ProductResponse getById(Long id) {
        return ProductResponse.from(findByIdOrThrow(id));
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> search(String nameFilter, Pageable pageable) {
        String filter = nameFilter == null ? "" : nameFilter;
        return productRepository.findByNameContainingIgnoreCase(filter, pageable)
                .map(ProductResponse::from);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> lowStock(int threshold) {
        return productRepository.findLowStock(threshold).stream()
                .map(ProductResponse::from)
                .toList();
    }

    /**
     * THE {@code @Cacheable} STOCK-LOOKUP METHOD (Module 8's required
     * centerpiece). {@code value = "productStock"} names the cache (see
     * {@code CacheConfig}, which gives this specific cache name a 2-minute
     * TTL distinct from the 10-minute default); {@code key = "#sku"} means
     * Spring stores/looks up entries keyed by the {@code sku} argument
     * (default key generation would use ALL arguments — explicit is safer
     * once a method has more than one parameter, so a key-relevant change
     * to the method signature doesn't silently change the cache key
     * shape).
     *
     * ON A CACHE HIT: this method body NEVER RUNS — Spring's AOP proxy
     * intercepts the call before it reaches this code and returns the
     * cached value directly, so a hit costs one Redis round-trip and zero
     * DB queries.
     *
     * ON A CACHE MISS: the method runs normally (one DB query via
     * {@code findBySku}), and Spring caches the returned value under
     * {@code sku} afterward — this is the "aside"/"lazy-loading" part of
     * cache-aside: the cache is only ever populated as a side effect of a
     * real read that already had to happen, never pre-warmed proactively
     * by this method itself.
     */
    @Cacheable(value = "productStock", key = "#sku")
    @Transactional(readOnly = true)
    public int getStockBySku(String sku) {
        return productRepository.findBySku(sku)
                .map(Product::getStockQuantity)
                .orElseThrow(() -> ResourceNotFoundException.forId("Product", sku));
    }

    /**
     * {@code @CacheEvict} REMOVES (does not update) the stale entry —
     * simpler and safer than trying to compute+write the new value into
     * the cache directly (which risks the cache and DB disagreeing if the
     * surrounding transaction later rolls back). The next
     * {@code getStockBySku} call after this simply misses and repopulates
     * from the now-updated DB row.
     *
     * Used by {@code OrderService.placeOrder} — takes a {@code productId}
     * (that's what {@code OrderLineRequest} carries) rather than a
     * {@code sku}, and RETURNS the managed {@code Product} entity so the
     * caller can read its current price/name to build an {@code OrderLine}
     * without a second query.
     *
     * WHY {@code key = "#result.sku"} INSTEAD OF {@code "#productId"}: the
     * cache is keyed by SKU everywhere else ({@code getStockBySku},
     * {@code restock}) — evicting by {@code productId} here would leave the
     * SKU-keyed entry untouched (a cache-key mismatch bug that's easy to
     * introduce when one code path naturally has an id and another has a
     * sku). {@code #result} refers to this method's return value and is
     * only available because {@code @CacheEvict} evicts AFTER the method
     * returns by default ({@code beforeInvocation = false}) — if this were
     * changed to evict beforehand (e.g. to guarantee eviction even when the
     * method throws), {@code #result} would not yet exist and this SpEL
     * expression would fail at runtime, which is worth knowing before
     * "simplifying" this annotation.
     */
    @CacheEvict(value = "productStock", key = "#result.sku")
    @Transactional
    public Product reserveStock(Long productId, int quantity) {
        Product product = findByIdOrThrow(productId);
        product.decrementStock(quantity, product.getSku());
        // No explicit save() call needed: `product` is a MANAGED entity
        // (loaded within this @Transactional method's persistence
        // context) — Hibernate's "dirty checking" detects the field
        // change and issues an UPDATE automatically at flush/commit time.
        // This is a common point of confusion for developers new to JPA:
        // forgetting save() is usually NOT a bug for entities already
        // loaded inside a transaction, but IS a bug for a detached entity
        // (e.g. one built in a controller from a DTO and never loaded via
        // the repository in this transaction) — see EXPLANATION.md.
        return product;
    }

    @CacheEvict(value = "productStock", key = "#sku")
    @Transactional
    public void restock(String sku, int quantity) {
        Product product = productRepository.findBySku(sku)
                .orElseThrow(() -> ResourceNotFoundException.forId("Product", sku));
        product.restock(quantity);
    }

    Product findByIdOrThrow(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forId("Product", id));
    }
}
