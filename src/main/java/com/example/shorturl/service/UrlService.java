package com.example.shorturl.service;

import com.example.shorturl.model.UrlMappingPO;
import com.example.shorturl.repository.UrlMappingRepository;
import com.example.shorturl.util.Base62;
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
                .map(UrlMappingPO::getShortCode)
                .orElseGet(() -> {
                    long id = idGenerator.nextId();
                    String shortCode = Base62.encode(id);

                    UrlMappingPO urlMappingPO = UrlMappingPO.builder()
                            .id(id)
                            .shortCode(shortCode)
                            .longUrl(longUrl)
                            .createdAt(LocalDateTime.now())
                            .expireAt(LocalDateTime.now().plusMonths(1)) // 1 month TTL
                            .clickCount(0)
                            .build();

                    repository.save(urlMappingPO);
                    return shortCode;
                });
    }

    public Optional<String> getLongUrl(String shortCode) {
        return repository.findByShortCode(shortCode)
                .map(urlMappingPO -> {
                    // Update click count (Async in production)
                    urlMappingPO.setClickCount(urlMappingPO.getClickCount() + 1);
                    repository.save(urlMappingPO);
                    return urlMappingPO.getLongUrl();
                });
    }
}
