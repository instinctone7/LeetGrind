package com.leetgrind.groups;

import java.time.Instant;
import java.util.List;

import com.leetgrind.catalog.ProblemCatalog.Difficulty;
import com.leetgrind.platform.ApiErrors.Failure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class CompetitionController {
    private static final String FIRST = "9223372036854775807";
    private final ChallengeService challenges;
    private final GroupActivity activity;

    CompetitionController(ChallengeService challenges, GroupActivity activity) {
        this.challenges = challenges;
        this.activity = activity;
    }

    @GetMapping("/groups/{groupId}/challenges")
    List<ChallengeService.Overview> challenges(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
            @RequestParam(defaultValue = "live") String mode, @RequestParam(defaultValue = FIRST) long before) {
        return challenges.list(groupId, actor(jwt), mode, before);
    }

    @PostMapping("/groups/{groupId}/challenges")
    @ResponseStatus(HttpStatus.CREATED)
    ChallengeService.View create(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
                                 @Valid @RequestBody ChallengeRequest request) {
        if (request.kind() != ChallengeService.Kind.DUEL || !"demo".equals(request.mode())) {
            throw new Failure(HttpStatus.NOT_FOUND, "challenge_not_found", "This competition is unavailable.");
        }
        return challenges.create(groupId, actor(jwt), request.settings());
    }

    @GetMapping("/groups/{groupId}/challenges/{challengeId}")
    ChallengeService.View challenge(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId, @PathVariable long challengeId) {
        return requireDuel(challenges.detail(groupId, actor(jwt), challengeId));
    }

    @PostMapping("/groups/{groupId}/duels")
    @ResponseStatus(HttpStatus.CREATED)
    ChallengeService.View duel(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
                               @Valid @RequestBody DuelRequest request) {
        long account = actor(jwt);
        long opponent;
        try {
            opponent = Long.parseLong(request.inviteeId());
        } catch (NumberFormatException invalid) {
            throw new Failure(HttpStatus.BAD_REQUEST, "invalid_challenge", "Choose a valid group member.");
        }
        var problem = challenges.resolveDuelProblem(groupId, account, request.problemUrl());
        return challenges.createDuel(groupId, account, opponent, problem, request.durationMinutes(), request.stakeXp());
    }

    @GetMapping("/groups/{groupId}/duels/summary")
    ChallengeService.DuelSummary duelSummary(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId) {
        return challenges.duelSummary(groupId, actor(jwt));
    }

    @PostMapping("/groups/{groupId}/challenges/{challengeId}/enrollment")
    ChallengeService.View enroll(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId, @PathVariable long challengeId) {
        requireDuel(challenges.detail(groupId, actor(jwt), challengeId));
        return challenges.enroll(groupId, actor(jwt), challengeId);
    }

    private static ChallengeService.View requireDuel(ChallengeService.View view) {
        if (view.challenge().kind() != ChallengeService.Kind.DUEL) {
            throw new Failure(HttpStatus.NOT_FOUND, "challenge_not_found", "This competition is unavailable.");
        }
        return view;
    }

    @GetMapping("/me/notifications")
    List<GroupActivity.Notice> inbox(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = FIRST) long before) {
        return activity.inbox(actor(jwt), before);
    }

    @PostMapping("/me/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(@AuthenticationPrincipal Jwt jwt, @PathVariable long notificationId) {
        activity.read(actor(jwt), notificationId);
    }

    private static long actor(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    record DuelRequest(@NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String inviteeId,
                       @NotBlank @Size(max = 300) String problemUrl,
                       @NotNull @Min(10) @Max(120) Integer durationMinutes,
                       @NotNull @Min(5) @Max(100) Integer stakeXp) {}

    record ChallengeRequest(@NotBlank @Size(max = 80) String title, @NotNull ChallengeService.Kind kind,
                            @NotNull ChallengeService.Rule rule, @Pattern(regexp = "live|demo") String mode,
                            @Pattern(regexp = "[1-9][0-9]{0,18}") String inviteeId,
                            @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 128) String problemSlug,
                            Difficulty difficulty, @Min(1) @Max(100) Integer target, Instant startsAt, @NotNull Instant endsAt) {
        ChallengeService.Settings settings() {
            Long invitee;
            try {
                invitee = inviteeId == null ? null : Long.valueOf(inviteeId);
            } catch (NumberFormatException invalid) {
                throw new Failure(HttpStatus.BAD_REQUEST, "invalid_challenge", "Choose a valid group member.");
            }
            return new ChallengeService.Settings(title, kind, rule, mode == null ? "live" : mode, invitee,
                    problemSlug, difficulty, target, startsAt, endsAt);
        }
    }
}
