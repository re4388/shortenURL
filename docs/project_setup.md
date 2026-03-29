# Project Setup - Spring Boot + MongoDB Short URL Service

## Overview

- A lightweight URL shortening microservice built with Spring Boot 3.2 and MongoDB. 
- The service provides a REST API to shorten long URLs into compact, Base62-encoded short codes, and redirects short codes back to their original long URLs.

---

## Project Structure

```
shortURL/
├── pom.xml
└── src/main/java/com/example/shorturl/
    ├── ShortUrlApplication.java          # Application entry point
    ├── controller/
    │   └── UrlController.java            # REST endpoints
    ├── service/
    │   └── UrlService.java                # Business logic
    ├── model/
    │   └── UrlMapping.java                # MongoDB document model
    ├── repository/
    │   └── UrlMappingRepository.java      # Spring Data MongoDB repository
    └── util/
        └── Base62.java                   # Base62 encode/decode utility
```

---

## Dependencies (pom.xml)

### Spring Boot 3.2.0 | Java 17

| Dependency | Purpose |
|---|---|
| `spring-boot-starter-data-mongodb` | MongoDB integration via Spring Data |
| `spring-boot-starter-web` | REST API via Spring MVC |
| `lombok` | Auto-generation of getters, setters, builders |
| `spring-boot-starter-test` | Unit testing support |

### Key transitive dependencies
- `spring-data-mongo` — MongoDB repository abstraction
- `spring-web` — HTTP request handling
- `jackson` — JSON serialization (configured to write dates as ISO strings)

---

## Configuration (application.yml)

```yaml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/shorturl
  jackson:
    serialization:
      write_dates_as_timestamps: false
```

- MongoDB is expected at `localhost:27017`
- Database name: `shorturl`
- Jackson serializes `LocalDateTime` as ISO-8601 strings (not epoch timestamps)

---

## Model — UrlMapping

File: `src/main/java/com/example/shorturl/model/UrlMapping.java`

```java
@Document(collection = "url_mappings")
public class UrlMapping {
    @Id
    private String id;              // MongoDB auto-generated ObjectId

    @Indexed(unique = true)
    private String shortCode;      // Base62-encoded short code (unique index)

    private String longUrl;        // Original long URL

    private LocalDateTime createdAt;   // Creation timestamp

    @Indexed(expireAfterSeconds = 0)
    private LocalDateTime expireAt;     // TTL index — document auto-deleted when reached

    private long clickCount;        // Number of redirects
}
```

### Indexes
- **Unique index on `shortCode`** — ensures no duplicate short codes
- **TTL index on `expireAt`** — MongoDB automatically removes expired documents (default TTL: 1 month after creation)

---

## Controller — UrlController

File: `src/main/java/com/example/shorturl/controller/UrlController.java`

### Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/v1/urls` | Shorten a URL |
| `GET` | `/{shortCode}` | Redirect to the original URL |

#### POST /api/v1/urls

Request body: raw JSON string (the long URL).

```bash
curl -X POST http://localhost:8080/api/v1/urls \
  -H "Content-Type: text/plain" \
  -d "https://www.example.com/some/very/long/path"
```

Response: `200 OK` with the short code as plain text.

#### GET /{shortCode}

```bash
curl -L http://localhost:8080/abc123
```

Returns a `302 Found` redirect to the stored long URL. Throws `RuntimeException` (500) if the short code is not found.

---

## Service — UrlService

File: `src/main/java/com/example/shorturl/service/UrlService.java`

### Logic

1. **Deduplication** — if the long URL already exists, return the existing `shortCode` (no duplicate rows)
2. **ID generation** (current placeholder) — uses `UUID.randomUUID().getMostSignificantBits()` to produce a numeric ID, then encodes it with Base62
3. **Storage** — saves a new `UrlMapping` document with a 1-month TTL
4. **Redirect tracking** — increments `clickCount` on every redirect

### Current ID Generation (Placeholder)

```java
long id = Math.abs(UUID.randomUUID().getMostSignificantBits());
String shortCode = Base62.encode(id);
```

> **Note:** The UUID-based approach is used as a placeholder. It is not monotonically increasing and collisions are theoretically possible. See `docs/snowflake_implementation.md` for the planned Snowflake ID replacement.

---

## Base62 Utility

File: `src/main/java/com/example/shorturl/util/Base62.java`

Encodes a non-negative `long` integer into a URL-safe alphanumeric string.

### Charset

| Index | Characters |
|---|---|
| 0–9 | `0` – `9` |
| 10–35 | `a` – `z` |
| 36–61 | `A` – `Z` |

### Methods

```java
// Encode: long (0) → Base62 string
String encode(long num)

// Decode: Base62 string → long
long decode(String str)
```

### Example

```
encode(125)   → "21"       (2×62 + 1)
encode(10000) → "2Bi"     (2×62² + 11×62 + 18)
decode("2Bi") → 10000
```

With a 63-bit unsigned `long`, Base62 can represent IDs up to approximately **10 characters**, which is the practical target for short URL codes.

---

## Repository — UrlMappingRepository

File: `src/main/java/com/example/shorturl/repository/UrlMappingRepository.java`

```java
@Repository
public interface UrlMappingRepository extends MongoRepository<UrlMapping, String> {
    Optional<UrlMapping> findByShortCode(String shortCode);
    Optional<UrlMapping> findByLongUrl(String longUrl);
}
```

Extends Spring Data MongoDB's `MongoRepository`, providing:
- CRUD operations (save, delete, findById)
- Derived query methods for `findByShortCode` and `findByLongUrl`

---

## Running the Application

```bash
# Ensure MongoDB is running locally
mongod --dbpath /data/db

# Start the application
cd shortURL
./mvnw spring-boot:run

# Or with Maven
mvn spring-boot:run
```

The service will start on `http://localhost:8080`.

---

## Known Limitations

- ID generation is UUID-based (non-monotonic, collision risk)
- Click count update is synchronous (not async)
- No custom short code support
- No authentication / rate limiting
- `expireAt` defaults to 1 month; no per-URL TTL customization

These items will be addressed in future iterations, starting with the Snowflake ID generator (see `docs/snowflake_implementation.md`).
