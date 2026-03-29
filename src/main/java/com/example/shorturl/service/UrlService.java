package com.example.shorturl.service;

import com.example.shorturl.config.CacheConfig;
import com.example.shorturl.model.DailyStatsPO;
import com.example.shorturl.model.UrlCreateRequest;
import com.example.shorturl.model.UrlMappingPO;
import com.example.shorturl.repository.DailyStatsRepository;
import com.example.shorturl.repository.UrlMappingRepository;
import com.example.shorturl.util.Base62;
import com.example.shorturl.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UrlService {
    public static final String CLICK_COUNT_PREFIX = "url:click:";
    public static final String NULL_VALUE = "NOT_FOUND";
    private static final long MAX_TTL_DAYS = 365 * 2; // 2 years limit

    private final UrlMappingRepository repository;
    private final DailyStatsRepository dailyStatsRepository;
    private final SnowflakeIdGenerator idGenerator;
    private final CacheManager l1CacheManager;
    private final CacheManager l2CacheManager;
    private final StringRedisTemplate redisTemplate;

    // Local locks to prevent cache stampede for the same shortCode on this instance
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Map<LocalDate, Long> getDailyStats(String shortCode, int days) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(days - 1);

        List<DailyStatsPO> stats = dailyStatsRepository.findByShortCodeAndDateBetweenOrderByDateAsc(shortCode, start, end);

        // Map to response format
        return stats.stream()
                .collect(Collectors.toMap(DailyStatsPO::getDate, DailyStatsPO::getClickCount));
    }

    public String shortenUrl(UrlCreateRequest request) {
        String longUrl = request.getLongUrl();
        LocalDateTime expireAt = calculateExpiration(request);

        // check if longUrl already exists
        return repository.findByLongUrl(longUrl)
                .map(mapping -> {
                    // Update expiration if new one is provided (Strategy: Overwrite)
                    mapping.setExpireAt(expireAt);
                    repository.save(mapping);

                    // Evict potential NULL cache
                    evictFromCaches(mapping.getShortCode());
                    // Pre-warm cache
                    putInCaches(mapping.getShortCode(), longUrl);

                    return mapping.getShortCode();
                })
                .orElseGet(() -> {
                    long id = idGenerator.nextId();
                    String shortCode = Base62.encode(id);

                    UrlMappingPO mapping = UrlMappingPO.builder()
                            .id(id)
                            .shortCode(shortCode)
                            .longUrl(longUrl)
                            .createdAt(LocalDateTime.now())
                            .expireAt(expireAt)
                            .clickCount(0)
                            .build();

                    repository.save(mapping);
                    evictFromCaches(shortCode);
                    putInCaches(shortCode, longUrl);
                    return shortCode;
                });
    }

    private LocalDateTime calculateExpiration(UrlCreateRequest request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime maxLimit = now.plusDays(MAX_TTL_DAYS);
        LocalDateTime expireAt;

        if (request.getExpireAtTimestamp() != null) {
            expireAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(request.getExpireAtTimestamp()), ZoneId.systemDefault());
        } else if (request.getTtlSeconds() != null) {
            expireAt = now.plusSeconds(request.getTtlSeconds());
        } else {
            expireAt = now.plusMonths(1); // Default
        }

        // Enforce system maximum limit
        return expireAt.isAfter(maxLimit) ? maxLimit : expireAt;
    }

    @Deprecated
    public String shortenUrl(String longUrl) {
        return shortenUrl(UrlCreateRequest.builder().longUrl(longUrl).build());
    }

    public Optional<String> getLongUrl(String shortCode) {
        // 1. Try L1 Cache (Caffeine)
        Cache l1Cache = l1CacheManager.getCache(CacheConfig.L1_CACHE_NAME);
        if (l1Cache != null) {
            String l1Val = l1Cache.get(shortCode, String.class);
            if (l1Val != null) {
                if (NULL_VALUE.equals(l1Val)) return Optional.empty();
                incrementClickCount(shortCode);
                return Optional.of(l1Val);
            }
        }

        // 2. Try L2 Cache (Redis)
        Cache l2Cache = l2CacheManager.getCache(CacheConfig.L2_CACHE_NAME);
        if (l2Cache != null) {
            String l2Val = l2Cache.get(shortCode, String.class);
            if (l2Val != null) {
                if (NULL_VALUE.equals(l2Val)) {
                    if (l1Cache != null) l1Cache.put(shortCode, NULL_VALUE);
                    return Optional.empty();
                }
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
                        if (NULL_VALUE.equals(val)) return Optional.empty();
                        incrementClickCount(shortCode);
                        return Optional.of(val);
                    }
                }

                Optional<UrlMappingPO> result = repository.findByShortCode(shortCode);
                if (result.isPresent()) {
                    UrlMappingPO mapping = result.get();
                    String longUrl = mapping.getLongUrl();
                    putInCaches(shortCode, longUrl);
                    incrementClickCount(shortCode);
                    return Optional.of(longUrl);
                } else {
                    // Cache the absence of this URL to prevent penetration
                    putInCaches(shortCode, NULL_VALUE);
                    return Optional.empty();
                }
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

    private void evictFromCaches(String shortCode) {
        Cache l1 = l1CacheManager.getCache(CacheConfig.L1_CACHE_NAME);
        Cache l2 = l2CacheManager.getCache(CacheConfig.L2_CACHE_NAME);
        if (l1 != null) l1.evict(shortCode);
        if (l2 != null) l2.evict(shortCode);
    }
}
