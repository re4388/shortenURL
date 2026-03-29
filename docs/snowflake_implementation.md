# Snowflake ID Generator Implementation Plan

## Overview

Replace the current UUID-based ID generation in `UrlService` with a **Twitter Snowflake**-style distributed ID generator. This ensures:

- **Monotonically increasing IDs** — safe for ordered indexing and time-based sorting
- **Globally unique IDs** across distributed nodes — no coordination with a central database needed
- **High throughput** — up to ~4,096 IDs per millisecond per worker

---

## Snowflake 64-bit Structure

Each ID is a signed 64-bit (8-byte) integer broken down as follows:

```
+------+------+------+------+
| sign | timestamp  | worker | seq  |
+------+------+------+------+
  1        41          10     12
  bit      bits         bits  bits
```

| Field | Bits | Range | Description |
|---|---|---|---|
| Sign | 1 | `0` | Always `0`; ensures unsigned 64-bit |
| Timestamp | 41 | 0 – `2^41 - 1` | Milliseconds since epoch |
| Worker ID | 10 | 0 – `2^10 - 1` (0–1023) | Identifies the generating node |
| Sequence | 12 | 0 – `2^12 - 1` (0–4095) | Per-ms incrementing counter |

### Overflow Behavior
- **Sequence overflow (> 4095/ms):** Wait until next millisecond
- **Timestamp overflow (~69.7 years from epoch):** Will not occur within planning horizon

---

## Epoch

Custom epoch: **2026-03-29 00:00:00 UTC**

This gives the project a comfortable operational window of approximately **69.7 years** from the epoch before timestamp rollover.

```java
private static final long EPOCH = LocalDateTime.of(2026, 3, 29, 0, 0, 0)
                                          .toInstant(ZoneOffset.UTC)
                                          .toEpochMilli();  // = 1743216000000
```

---

## Component Design

### 1. SnowflakeIdGenerator Class

```
src/main/java/com/example/shorturl/util/SnowflakeIdGenerator.java
```

#### State

```java
private final long workerId;          // Fixed per instance (0–1023)
private long lastTimestamp = -1L;      // Last used timestamp (ms)
private short sequence = 0;           // Sequence counter within same ms
private final Object lock = new Object();
```

#### Key Method: `nextId()`

```java
public long nextId() {
    synchronized (lock) {
        long now = System.currentTimeMillis();

        if (now < lastTimestamp) {
            // Clock moved backwards — handle clock skew
            // (see Error Handling section below)
        }

        if (now == lastTimestamp) {
            sequence = (short) ((sequence + 1) & 0xFFF);  // Mask to 12 bits
            if (sequence == 0) {
                // Sequence overflow — spin until next millisecond
                while (now == lastTimestamp) {
                    now = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = now;
        return ((now - EPOCH) << 22) | (workerId << 12) | sequence;
    }
}
```

#### Bit Shifts

| Component | Shift |
|---|---|
| Timestamp (41 bits) | `22` (reserve LSB 22 bits for worker + sequence) |
| Worker ID (10 bits) | `12` (reserve LSB 12 bits for sequence) |
| Sequence (12 bits) | `0` |

---

### 2. Worker ID Allocation

Each running instance of the service must receive a **unique `workerId`** (0–1023).

#### Options (in order of preference)

| Strategy | Description | Pros | Cons |
|---|---|---|---|
| **MongoDB atomic counter** | Fetch and increment a `worker_counter` document on startup | Simple, always unique, persistent | Requires MongoDB on every startup; slight latency |
| **Environment variable** | `WORKER_ID` env var injected by orchestration (K8s, Docker) | Zero startup cost, stateless | Manual coordination needed across nodes |
| **Consul / etcd** | Lock a key in a distributed coordination service | Fully distributed, automatic | Additional infrastructure dependency |

#### Recommended: MongoDB Atomic Counter

```java
// On startup
@Service
public class WorkerIdProvider {
    private final MongoTemplate mongoTemplate;

    @PostConstruct
    public long acquireWorkerId() {
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .returnNew(true)
                .upsert(true);
        Query query = new Query(Criteria.where("_id").is("worker_counter"));
        Update update = new Update().inc("value", 1);
        WorkerCounter counter = mongoTemplate.findAndModify(
            query, update, options, WorkerCounter.class);
        return counter.getValue() % 1024;  // Wrap around at 1024
    }
}
```

> **Note:** If running multiple instances on the same `worker_counter` MongoDB, ensure the MongoDB replica set is used for consistency. Single-node MongoDB with a plain counter is sufficient for most single-region deployments.

---

### 3. Integration with UrlService

Replace the UUID-based ID generation in `UrlService`:

```java
@Service
@RequiredArgsConstructor
public class UrlService {
    private final UrlMappingRepository repository;
    private final SnowflakeIdGenerator idGenerator;

    public String shortenUrl(String longUrl) {
        return repository.findByLongUrl(longUrl)
                .map(UrlMapping::getShortCode)
                .orElseGet(() -> {
                    long id = idGenerator.nextId();       // Replace UUID approach
                    String shortCode = Base62.encode(id);
                    // ... save mapping
                });
    }
}
```

**Benefits:**
- IDs are monotonically increasing (good for MongoDB `_id` ordering)
- No UUID collisions
- Single method call to generate globally unique ID

---

## MongoDB Int64 Storage

### Problem

MongoDB's default `_id` field stores an **ObjectId** (12 bytes, not an integer). When using a Snowflake `long` as the document ID, MongoDB must store it as a **64-bit integer (Int64 / Long)**.

### Solution

Explicitly define the ID as `Long` in the model:

```java
@Id
private String id;   // Spring Data accepts String for _id, but stores as-is
```

However, for better type safety, use `MongoTemplate` for insert operations with explicit type mapping:

```java
Query query = new Query(Criteria.where("shortCode").is(shortCode));
Update update = new Update()
    .setOnInsert("id", idGenerator.nextId())  // Int64
    .set("longUrl", longUrl)
    .set("createdAt", LocalDateTime.now())
    .set("expireAt", LocalDateTime.now().plusMonths(1))
    .set("clickCount", 0L);
mongoTemplate.upsert(query, update, UrlMapping.class);
```

Alternatively, change the `id` field type to `Long` in `UrlMapping.java`:

```java
@Id
private Long id;   // MongoDB stores as Int64
```

### Driver Compatibility

The MongoDB Java driver (`mongodb-driver-sync`) correctly serializes Java `long` as **BSON Int64**. Verify the following:

- Spring Data MongoDB version is 4.x (ships with Spring Boot 3.2)
- MongoDB server version is 3.2+ (all modern versions support Int64 natively)

### Index on `_id`

MongoDB automatically creates a unique index on `_id`. Since Snowflake IDs are unique by design, this index will enforce uniqueness without any extra configuration.

---

## Error Handling

### Clock Skew (timestamp goes backwards)

**Scenario:** The system clock jumps backward (e.g., NTP correction, VM pause/resume).

**Risk:** If `lastTimestamp > currentTime`, the generated ID could collide with a previously generated ID (same timestamp + worker + sequence).

#### Strategy: Wait and Retry

```java
if (now < lastTimestamp) {
    // Clock moved backwards — wait until we are past lastTimestamp
    long diff = lastTimestamp - now;
    try {
        Thread.sleep(diff);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Clock rollback detected", e);
    }
    now = System.currentTimeMillis();
}
```

**Pros:** Simple, no data loss, no duplicate IDs
**Cons:** Introduces latency equal to the clock drift amount

#### Alternative Strategy: Accept Smaller ID

If the clock skew is small (a few ms), a slightly smaller ID than expected is acceptable in some use cases. The simpler **wait-and-retry** approach is recommended for this project.

### Sequence Overflow (> 4095/ms)

When `sequence` reaches 4095 and a new ID is requested in the same millisecond, the generator blocks until the clock moves to the next millisecond:

```java
while (now == lastTimestamp) {
    now = System.currentTimeMillis();
}
sequence = 0;
```

At 4096 IDs/ms (~4 billion IDs/day per worker), this limit is unlikely to be hit in practice for a URL shortening service.

### Worker ID Collision

If two instances receive the same `workerId`, IDs generated in the same millisecond could collide.

**Prevention:**
- Use the MongoDB atomic counter method (described above) for worker ID allocation
- If using environment variables, ensure deployment scripts assign unique IDs per instance

---

## Implementation Checklist

- [ ] Create `SnowflakeIdGenerator.java` with `nextId()` method
- [ ] Define custom epoch constant (`EPOCH = 1743216000000`)
- [ ] Implement clock skew handling (wait-and-retry)
- [ ] Implement sequence overflow handling
- [ ] Create `WorkerIdProvider` bean with MongoDB atomic counter
- [ ] Update `UrlService` to inject `SnowflakeIdGenerator` and use `nextId()`
- [ ] Change `UrlMapping.id` type from `String` to `Long` (or use `MongoTemplate` upsert)
- [ ] Write unit tests for `SnowflakeIdGenerator`:
      - Sequential calls return increasing IDs
      - Same millisecond produces incrementing sequences
      - Clock skew triggers wait-and-retry
      - Sequence overflow advances to next millisecond
- [ ] Verify MongoDB stores IDs as Int64 (use `bsonType: "long"` in validation if needed)
