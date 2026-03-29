package com.example.shorturl.config;

import com.example.shorturl.model.WorkerNode;
import com.example.shorturl.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SnowflakeConfig {
    private final MongoTemplate mongoTemplate;
    private final long MAX_WORKER_ID = 1023L;

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        long workerId = getOrAssignWorkerId();
        log.info("Assigned Worker ID: {}", workerId);
        return new SnowflakeIdGenerator(workerId);
    }

    private synchronized long getOrAssignWorkerId() {
        String hostIdentifier;
        try {
            // Priority: Env hostname > InetAddress
            hostIdentifier = System.getenv("HOSTNAME");
            if (hostIdentifier == null) {
                hostIdentifier = InetAddress.getLocalHost().getHostName();
            }
        } catch (UnknownHostException e) {
            hostIdentifier = UUID.randomUUID().toString();
        }

        WorkerNode existingNode = mongoTemplate.findOne(
                Query.query(Criteria.where("hostname").is(hostIdentifier)),
                WorkerNode.class);

        if (existingNode != null) {
            return existingNode.getWorkerId();
        }

        // Assign next available workerId (simple sequential search for example)
        for (long i = 0; i <= MAX_WORKER_ID; i++) {
            if (!mongoTemplate.exists(Query.query(Criteria.where("workerId").is(i)), WorkerNode.class)) {
                WorkerNode newNode = WorkerNode.builder()
                        .workerId(i)
                        .hostname(hostIdentifier)
                        .build();
                mongoTemplate.save(newNode);
                return i;
            }
        }

        throw new RuntimeException("No available workerWorker IDs found in the range 0-1023");
    }
}
