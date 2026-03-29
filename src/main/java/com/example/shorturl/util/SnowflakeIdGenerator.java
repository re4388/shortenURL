package com.example.shorturl.util;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SnowflakeIdGenerator {
    // Current bit structure: 1 sign + 41 timestamp + 10 workerId + 12 sequence

    // Epoch: 2026-03-29 00:00:00 UTC
    private final long EPOCH = 1743216000000L;

    private final long workerIdBits = 10L;
    private final long maxWorkerId = -1L ^ (-1L << workerIdBits); // 1023
    private final long sequenceBits = 12L;
    private final long maxSequence = -1L ^ (-1L << sequenceBits); // 4095

    private final long workerIdShift = sequenceBits;
    private final long timestampLeftShift = sequenceBits + workerIdBits;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > maxWorkerId) {
            throw new IllegalArgumentException(String.format("Worker ID must be between 0 and %d", maxWorkerId));
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long now = genCurrentTime();

        // Clock skew check
        if (now < lastTimestamp) {
            long offset = lastTimestamp - now;
            if (offset <= 5) {
                try {
                    wait(offset << 1);
                    now = genCurrentTime();
                    if (now < lastTimestamp) {
                        throw new RuntimeException("Clock moved backwards. Refusing to generate ID for " + offset + "ms");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException("Clock moved backwards too much. Refusing to generate ID for " + offset + "ms");
            }
        }

        if (lastTimestamp == now) {
            sequence = (sequence + 1) & maxSequence;
            if (sequence == 0) {
                // Sequence overflow, spin until next millisecond
                now = spinUntilNextMs(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = now;

        return ((now - EPOCH) << timestampLeftShift) |
               (workerId << workerIdShift) |
               sequence;
    }

    private long spinUntilNextMs(long lastTimestamp) {
        long now = genCurrentTime();
        while (now <= lastTimestamp) {
            now = genCurrentTime();
        }

        return now;
    }

    private long genCurrentTime() {
        return System.currentTimeMillis();
    }
}
