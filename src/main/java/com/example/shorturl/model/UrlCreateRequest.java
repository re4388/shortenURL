package com.example.shorturl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UrlCreateRequest {
    private String longUrl;

    // Optional: requested Time To Live in seconds.
    // If null, will use default (1 month).
    private Long ttlSeconds;

    // Optional: direct expiration timestamp
    // If provided, takes precedence over ttlSeconds.
    private Long expireAtTimestamp;
}
