package com.leetgrind.analytics;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AnalyticsController {
    private final AnalyticsService analytics;

    AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/api/v1/me/analytics")
    AnalyticsService.Analytics analytics(@AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(defaultValue = "live") String mode,
                                         @RequestParam(defaultValue = "all-time") String period,
                                         @RequestParam(required = false) String start) {
        return analytics.read(Long.parseLong(jwt.getSubject()), mode, period, start);
    }

    @GetMapping("/api/v1/me/practice-catalog")
    List<AnalyticsService.CatalogProblem> practiceCatalog(@AuthenticationPrincipal Jwt jwt,
                                                         @RequestParam(defaultValue = "live") String mode) {
        return analytics.practiceCatalog(Long.parseLong(jwt.getSubject()), mode);
    }
}
