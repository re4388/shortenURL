package com.example.shorturl.service;

import com.example.shorturl.model.UrlMapping;
import com.example.shorturl.repository.UrlMappingRepository;
import com.example.shorturl.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UrlService {
    private final UrlMappingRepository repository;
    private final SnowflakeIdGenerator idGenerator;

    public String shortenUrl(String longUrl) {
        // Simple strategy: check if longUrl already exists
        return repository.findByLongUrl(longUrl)
                .map(UrlMapping::getShortCode)
                .orElseGet(() -> {
                    long id = idGenerator.nextId();
                    String shortCode = Base62.encode(id);

                    UrlMapping mapping = UrlMapping.builder()
                            .id(id)
                            .shortCode(shortCode)
                            .longUrl(longUrl)
                            .createdAt(LocalDateTime.now())
                            .expireAt(LocalDateTime.now().plusMonths(1)) // 1 month TTL
                            .clickCount(0)
                            .build();

                    repository.save(mapping);
                    return shortCode;
                });
    }

    public Optional<String> getLongUrl(String shortCode) {
        return repository.findByShortCode(shortCode)
                .map(mapping -> {
                    // Update click count (Async in production)
                    mapping.setClickCount(mapping.getClickCount() + 1);
                    repository.save(mapping);
                    return mapping.getLongUrl();
                });
    }
}
