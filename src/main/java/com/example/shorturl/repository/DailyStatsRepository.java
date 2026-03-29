package com.example.shorturl.repository;

import com.example.shorturl.model.DailyStatsPO;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface DailyStatsRepository extends MongoRepository<DailyStatsPO, String> {
    List<DailyStatsPO> findByShortCodeAndDateBetweenOrderByDateAsc(String shortCode, LocalDate start, LocalDate end);
    Optional<DailyStatsPO> findByShortCodeAndDate(String shortCode, LocalDate date);
}
