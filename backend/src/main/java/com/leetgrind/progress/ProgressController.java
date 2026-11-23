package com.leetgrind.progress;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class ProgressController {
    private final ProgressService progress;

    ProgressController(ProgressService progress) {
        this.progress = progress;
    }

    @GetMapping("/progress")
    ProgressService.Summary progress(@AuthenticationPrincipal Jwt jwt) {
        return progress.summary(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/check-in")
    ProgressService.CheckIn checkIn(@AuthenticationPrincipal Jwt jwt) {
        return progress.checkIn(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/reward-events")
    List<ProgressService.RewardEvent> rewards(@AuthenticationPrincipal Jwt jwt) {
        return progress.rewardEvents(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/xp-entries")
    ProgressService.History history(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(required = false) String cursor) {
        return progress.history(Long.parseLong(jwt.getSubject()), limit, cursor);
    }
}
