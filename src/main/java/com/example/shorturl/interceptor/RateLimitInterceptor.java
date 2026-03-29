package com.example.shorturl.interceptor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;

    // Configuration (Can be moved to application.yml)
    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private static final String REDIS_KEY_PREFIX = "rate:limit:";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = getClientIp(request);
        String key = REDIS_KEY_PREFIX + ip;
        long now = System.currentTimeMillis();
        long windowStart = now - 60000; // 1-minute window

        try {
            // Sliding Window Implementation using Redis Sorted Set (ZSET)
            // 1. Remove timestamps older than the window
            redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart);

            // 2. Count timestamps in the current window
            Long currentRequests = redisTemplate.opsForZSet().zCard(key);

            if (currentRequests != null && currentRequests >= MAX_REQUESTS_PER_MINUTE) {
                log.warn("Rate limit exceeded for IP: {}. Current requests: {}", ip, currentRequests);
                return handleRateLimitExceeded(response);
            }

            // 3. Add current request timestamp
            redisTemplate.opsForZSet().add(key, String.valueOf(now), now);
            // 4. Set TTL for the whole set to prevent leftovers
            redisTemplate.expire(key, java.time.Duration.ofMinutes(2));

            return true;
        } catch (Exception e) {
            log.error("Error evaluating rate limit for IP: {}", ip, e);
            // Fail open: allow request if Redis is down (Optional safety choice)
            return true;
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xfHeader = request.getHeader("X-Forwarded-For");
        if (xfHeader == null) return request.getRemoteAddr();
        return xfHeader.split(",")[0];
    }

    private boolean handleRateLimitExceeded(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("text/plain");
        response.getWriter().write("Too many requests from your IP. Please try again in a minute.");
        return false;
    }
}
