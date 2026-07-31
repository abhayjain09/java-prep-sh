package com.interviewprep.orders.springapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.Map;

/**
 * Module 8 — Redis-backed {@code CacheManager} configuration.
 *
 * WHY A CUSTOM BEAN INSTEAD OF LETTING BOOT AUTO-CONFIGURE A DEFAULT
 * {@code RedisCacheManager}: Boot's auto-configured default works, but uses
 * ONE global TTL (or no expiration at all) for every {@code @Cacheable}
 * cache name in the app and Java's default (JDK) serialization for cached
 * values. Neither default is good enough for a real system:
 *
 * 1. DIFFERENT DATA NEEDS DIFFERENT TTLs. Stock levels (this module's
 *    {@code productStock} cache) change frequently and staleness has a
 *    direct business cost (selling something that's actually out of
 *    stock) — a short TTL. Slower-changing reference data elsewhere in a
 *    real system (e.g. a product catalog's category tree) can tolerate a
 *    much longer TTL. One global setting can't express that.
 * 2. JDK SERIALIZATION IS A BAD DEFAULT for a cache: it requires cached
 *    types to implement {@code Serializable}, the byte format is
 *    Java-specific (unreadable from `redis-cli GET`, impossible for a
 *    non-JVM service to consume even if it wanted to), and it's brittle
 *    across class version changes (a field added/removed can break
 *    deserialization of already-cached entries after a deploy).
 *    {@code GenericJackson2JsonRedisSerializer} stores plain JSON instead —
 *    inspectable directly in Redis, resilient to additive class changes,
 *    consumable by any language.
 */
@Configuration
public class CacheConfig {

    private static final String PRODUCT_STOCK_CACHE = "productStock";

    /**
     * TTL (TIME-TO-LIVE) CHOICE — THE STALENESS-VS-LOAD TRADE-OFF:
     * a SHORTER TTL means the cache reflects reality faster after a write
     * (less staleness) but the cache is invalidated by expiry more often,
     * pushing more reads back to Postgres (more DB load, defeating some of
     * the point of caching). A LONGER TTL protects the DB more but risks
     * serving stale stock counts for longer — in the worst case, telling a
     * customer an item is in stock when it just sold out elsewhere.
     *
     * 2 minutes here is a deliberately short TTL for stock data BECAUSE
     * this module ALSO uses explicit {@code @CacheEvict} on every mutation
     * path (see {@code ProductService.decrementStock}/{@code restock}) —
     * the TTL is a safety net for staleness the explicit eviction might
     * miss (e.g. a stock change made directly in the DB by a batch job
     * that bypasses the application, or a cache entry from a
     * since-crashed/rolled-back transaction), not the PRIMARY invalidation
     * mechanism. This combination (explicit eviction as primary,
     * short TTL as a backstop) is the cache-aside pattern this module
     * uses — see spring/README.md's Caching section for the full
     * cache-aside vs. write-through comparison and "the two hard things in
     * computer science are cache invalidation and naming things" framing.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues() // never cache "not found" — see README's null-caching note
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        RedisCacheConfiguration productStockConfig = defaultConfig.entryTtl(Duration.ofMinutes(2));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(Map.of(PRODUCT_STOCK_CACHE, productStockConfig))
                .build();
    }
}
