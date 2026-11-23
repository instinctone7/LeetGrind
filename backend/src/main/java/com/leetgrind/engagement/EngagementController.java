package com.leetgrind.engagement;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EngagementController {
    private final EngagementService service;

    EngagementController(EngagementService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/me/rewards")
    EngagementService.Rewards rewards(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "live") String mode) {
        return service.rewards(Long.parseLong(jwt.getSubject()), mode);
    }
}
