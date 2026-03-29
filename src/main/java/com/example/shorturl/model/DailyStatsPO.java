package com.example.shorturl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "daily_stats")
@CompoundIndex(name = "sc_date_idx", def = "{'shortCode': 1, 'date': 1}", unique = true)
public class DailyStatsPO {
    @Id
    private String id;
    private String shortCode;
    private LocalDate date;
    private long clickCount;
}
