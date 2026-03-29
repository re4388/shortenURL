package com.example.shorturl.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlacklistManager {

    private final StringRedisTemplate redisTemplate;
    public static final String REDIS_BLACKLIST_KEY = "url:blacklist";

    // In-memory Trie node for suffix matching
    private static class TrieNode {
        Map<String, TrieNode> children = new HashMap<>();
        boolean isEndOfRule = false;
    }

    private final TrieNode root = new TrieNode();
    private final Set<String> exactBlacklist = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Self-domain (will be injected/configured)
    private static final String MY_DOMAIN = "localhost"; // Example, replace with real domain

    @PostConstruct
    public void init() {
        // 1. Always block self
        addRule(MY_DOMAIN);
        // 2. Default blocking of common shorteners to prevent recursive aliasing
        addRule("bit.ly");
        addRule("tinyurl.com");
        addRule("t.co");

        // Force initial sync from Redis
        syncFromRedis();
    }

    /**
     * Splits domain by dots (e.g., "mail.evil.com" -> ["com", "evil", "mail"])
     * and adds to the suffix Trie.
     */
    public void addRule(String domain) {
        if (domain == null || domain.isEmpty()) return;
        domain = domain.toLowerCase(Locale.ROOT);

        String[] parts = domain.split("\\.");
        TrieNode current = root;

        // Traverse backwards to match suffixes (e.g. *.evil.com)
        for (int i = parts.length - 1; i >= 0; i--) {
            current = current.children.computeIfAbsent(parts[i], k -> new TrieNode());
        }
        current.isEndOfRule = true;
        exactBlacklist.add(domain);
        log.info("Registered blacklist rule: {}", domain);
    }

    /**
     * Checks if a host is blocked.
     * Matches either exact host or any parent domain in the Trie.
     */
    public boolean isBlocked(String host) {
        if (host == null || host.isEmpty()) return true;
        host = host.toLowerCase(Locale.ROOT);

        // 1. Direct check
        if (exactBlacklist.contains(host)) return true;

        // 2. Suffix check (e.g., if "evil.com" is blocked, "sub.evil.com" is blocked)
        String[] parts = host.split("\\.");
        TrieNode current = root;
        for (int i = parts.length - 1; i >= 0; i--) {
            current = current.children.get(parts[i]);
            if (current == null) break;
            if (current.isEndOfRule) return true;
        }

        // 3. Redis distributed blacklist check (for exact domains)
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(REDIS_BLACKLIST_KEY, host));
    }

    /**
     * Periodic sync from Redis to update in-memory cache.
     * Use external agency APIs or DB updates to populate this Redis Set.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void syncFromRedis() {
        Set<String> members = redisTemplate.opsForSet().members(REDIS_BLACKLIST_KEY);
        if (members != null) {
            members.forEach(this::addRule);
        }
        log.info("Synced blacklist rules from Redis. Total in-memory rules: {}", exactBlacklist.size());
    }
}
