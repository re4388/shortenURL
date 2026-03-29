package com.example.shorturl.controller;

import com.example.shorturl.service.UrlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.view.RedirectView;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class UrlController {
    private final UrlService urlService;

    @PostMapping("/api/v1/urls")
    public ResponseEntity<String> create(@RequestBody String longUrl) {
        String shortCode = urlService.shortenUrl(longUrl);
        return ResponseEntity.ok(shortCode);
    }

    @GetMapping("/{shortCode}")
    public RedirectView redirect(@PathVariable String shortCode) {
        return urlService.getLongUrl(shortCode)
                .map(longUrl -> new RedirectView(longUrl, false)) // 302 Found
                .orElseThrow(() -> new RuntimeException("URL not found"));
    }

    @GetMapping("/api/v1/urls/{shortCode}/stats")
    public ResponseEntity<Map<LocalDate, Long>> getStats(
            @PathVariable String shortCode,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(urlService.getDailyStats(shortCode, days));
    }
}
