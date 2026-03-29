package com.example.shorturl.repository;

import com.example.shorturl.model.UrlMappingPO;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UrlMappingRepository extends MongoRepository<UrlMappingPO, Long> {
    Optional<UrlMappingPO> findByShortCode(String shortCode);
    Optional<UrlMappingPO> findByLongUrl(String longUrl);
}
