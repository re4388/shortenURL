package com.example.shorturl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "worker_registry")
public class WorkerNodePO {
    @Id
    private String id; // Use POD_NAME or internal UUID
    private long workerId;
    private String hostname;
}
