package com.example.shorturl.service;

import com.example.shorturl.config.CacheConfig;
import com.example.shorturl.model.UrlMappingPO;
import com.example.shorturl.repository.UrlMappingRepository;
import com.example.shorturl.util.Base62;
import com.example.shorturl.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {
    public static final String CLICK_COUNT_PREFIX = "url:click:";

    private final UrlMappingRepository repository;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheManager l1CacheManager;
    private final CacheManager l2CacheManager;
    private final StringRedisTemplate redisTemplate;

    // Local locks to prevent cache stampede for the same shortCode on this instance
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public String shortenUrl(String longUrl) {
        // Simple strategy: check if longUrl already exists
        return repository.findByLongUrl(longUrl)
                .map(UrlMappingPO::getShortCode)
                .orElseGet(() -> {
                    long id = idGenerator.nextId();
                    String shortCode = Base62.encode(id);

                    UrlMappingPO urlMappingPO = UrlMappingPO.builder()
                            .id(id)
                            .shortCode(shortCode)
                            .longUrl(longUrl)
                            .createdAt(LocalDateTime.now())
                            .expireAt(LocalDateTime.now().plusMonths(1)) // 1 month TTL
                            .clickCount(0)
                            .build();

                    repository.save(urlMappingPO);

                    // Pre-warm cache
                    putInCaches(shortCode, longUrl);

                    return shortCode;
                });
    }

    public Optional<String> getLongUrl(String shortCode) {
        // 1. Try L1 Cache (Caffeine)
        Cache l1Cache = l1CacheManager.getCache(CacheConfig.L1_CACHE_NAME);
        if (l1Cache != null) {
            String l1Val = l1Cache.get(shortCode, String.class);
            if (l1Val != null) {
                incrementClickCount(shortCode);
                return Optional.of(l1Val);
            }
        }

        // 2. Try L2 Cache (Redis)
        Cache l2Cache = l2CacheManager.getCache(CacheConfig.L2_CACHE_NAME);
        if (l2Cache != null) {
            String l2Val = l2Cache.get(shortCode, String.class);
            if (l2Val != null) {
                // Secondary backfill to L1
                if (l1Cache != null) l1Cache.put(shortCode, l2Val);
                incrementClickCount(shortCode);
                return Optional.of(l2Val);
            }
        }

        // 3. Cache Miss - Database (with Lock to prevent Stampede)
        Object lock = locks.computeIfAbsent(shortCode, k -> new Object());
        synchronized (lock) {
            try {
                // Double check after getting lock
                if (l1Cache != null) {
                    String val = l1Cache.get(shortCode, String.class);
                    if (val != null) {
                        incrementClickCount(shortCode);
                        return Optional.of(val);
                    }
                }

                return repository.findByShortCode(shortCode)
                        .map(urlMappingPO -> {
                            String longUrl = urlMappingPO.getLongUrl();
                            // Backfill caches
                            putInCaches(shortCode, longUrl);
                            incrementClickCount(shortCode);
                            return longUrl;
                        });
            } finally {
                locks.remove(shortCode);
            }
        }
    }

    private void incrementClickCount(String shortCode) {
        // Atomic increment in Redis (Asynchronous-style collection)
        redisTemplate.opsForValue().increment(CLICK_COUNT_PREFIX + shortCode);
    }

    private void putInCaches(String shortCode, String longUrl) {
        Cache l1 = l1CacheManager.getCache(CacheConfig.L1_CACHE_NAME);
        Cache l2 = l2CacheManager.getCache(CacheConfig.L2_CACHE_NAME);
        if (l1 != null) l1.put(shortCode, longUrl);
        if (l2 != null) l2.put(shortCode, longUrl);
    }
}
