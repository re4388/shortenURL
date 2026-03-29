package com.example.shorturl.service;

import com.example.shorturl.model.UrlMappingPO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@EnableScheduling
@RequiredArgsConstructor
public class BatchClickCountService {

    private final StringRedisTemplate redisTemplate;
    private final MongoTemplate mongoTemplate;

    // Cron expression: Every minute (adjust as needed)
    @Scheduled(fixedRate = 60000)
    public void syncClickCountToMongo() {
        log.debug("Starting batch sync of click counts from Redis to MongoDB");

        ScanOptions options = ScanOptions.scanOptions()
                .match(UrlService.CLICK_COUNT_PREFIX + "*")
                .count(100)
                .build();

        List<String> keysToDelete = new ArrayList<>();
        BulkOperations bulkOps = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, UrlMappingPO.class);
        boolean hasOperations = false;

        try (Cursor<String> cursor = redisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String countStr = redisTemplate.opsForValue().get(key);

                if (countStr != null) {
                    long count = Long.parseLong(countStr);
                    String shortCode = key.substring(UrlService.CLICK_COUNT_PREFIX.length());

                    // Prepare MongoDB atomic increment
                    Query query = Query.query(Criteria.where("shortCode").is(shortCode));
                    Update update = new Update().inc("clickCount", count);
                    bulkOps.updateOne(query, update);

                    keysToDelete.add(key);
                    hasOperations = true;
                }
            }
        } catch (Exception e) {
            log.error("Error during Redis scan for click counts", e);
        }

        if (hasOperations) {
            try {
                // 1. Execute Bulk Update in MongoDB
                bulkOps.execute();

                // 2. Delete keys from Redis only after successful DB update
                // Note: There's a tiny window for double-counting if delete fails,
                // but it's acceptable for click counts.
                redisTemplate.delete(keysToDelete);

                log.info("Successfully synced {} shortCodes click counts to MongoDB", keysToDelete.size());
            } catch (Exception e) {
                log.error("Failed to execute bulk update for click counts", e);
            }
        }
    }
}
