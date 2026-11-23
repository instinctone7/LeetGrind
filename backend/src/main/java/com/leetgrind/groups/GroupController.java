package com.leetgrind.groups;

import java.util.List;

import com.leetgrind.engagement.EngagementService;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class GroupController {
    private final GroupService groups;
    private final GroupInvites invites;
    private final GroupStandings standings;

    GroupController(GroupService groups, GroupInvites invites, GroupStandings standings) {
        this.groups = groups;
        this.invites = invites;
        this.standings = standings;
    }

    @GetMapping("/groups")
    Directory list(@AuthenticationPrincipal Jwt jwt) {
        return new Directory(groups.list(actor(jwt)), standings.demoAvailable());
    }

    @GetMapping("/groups/overview")
    List<GroupStandings.DirectoryMetrics> overview(@AuthenticationPrincipal Jwt jwt) {
        return standings.directoryMetrics(actor(jwt));
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    GroupService.Summary create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Settings settings) {
        return groups.create(actor(jwt), settings.name(), settings.description(), settings.visibility());
    }

    @GetMapping("/groups/public")
    List<GroupService.PublicGroup> discover(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        return groups.discover(actor(jwt), before);
    }

    @PostMapping("/groups/{groupId}/join")
    GroupService.Summary joinPublic(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId) {
        return groups.joinPublic(groupId, actor(jwt));
    }

    @GetMapping("/groups/{groupId}")
    GroupService.Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId) {
        return groups.detail(groupId, actor(jwt));
    }

    @PatchMapping("/groups/{groupId}")
    GroupService.Summary update(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
                                @Valid @RequestBody UpdatedSettings settings) {
        return groups.update(groupId, actor(jwt), settings.name(), settings.description());
    }

    @DeleteMapping("/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId) {
        groups.close(groupId, actor(jwt));
    }

    @PostMapping("/groups/{groupId}/ownership")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void transfer(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
                  @Valid @RequestBody NewOwner request) {
        groups.transfer(groupId, actor(jwt), Long.parseLong(request.accountId()));
    }

    @DeleteMapping("/groups/{groupId}/members/{accountId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId, @PathVariable long accountId) {
        groups.remove(groupId, actor(jwt), accountId);
    }

    @PostMapping("/group-memberships")
    GroupService.Summary join(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Join request) {
        return invites.redeem(actor(jwt), request.code());
    }

    @GetMapping("/groups/{groupId}/invites")
    List<GroupInvites.Invite> invitations(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId) {
        return invites.list(groupId, actor(jwt));
    }

    @PostMapping("/groups/{groupId}/invites")
    @ResponseStatus(HttpStatus.CREATED)
    GroupInvites.Created invite(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
                                @Valid @RequestBody InviteSettings settings) {
        return invites.create(groupId, actor(jwt), settings.expiresInHours(), settings.maxUses());
    }

    @DeleteMapping("/groups/{groupId}/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId, @PathVariable long inviteId) {
        invites.revoke(groupId, actor(jwt), inviteId);
    }

    @GetMapping("/groups/{groupId}/leaderboard")
    GroupStandings.Page leaderboard(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
                                    @RequestParam(defaultValue = "weekly") String period,
                                    @RequestParam(required = false) String week,
                                    @RequestParam(required = false) String month,
                                    @RequestParam(defaultValue = "live") String mode,
                                    @RequestParam(defaultValue = "20") int limit,
                                    @RequestParam(required = false) String cursor) {
        return standings.page(groupId, actor(jwt), period, week, month, mode, limit, cursor);
    }

    @GetMapping("/leaderboard")
    GroupStandings.Page individualLeaderboard(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(defaultValue = "weekly") String period,
                                              @RequestParam(required = false) String week,
                                              @RequestParam(required = false) String month,
                                              @RequestParam(defaultValue = "20") int limit,
                                              @RequestParam(required = false) String cursor) {
        return standings.individualPage(actor(jwt), period, week, month, limit, cursor);
    }

    @GetMapping("/groups/{groupId}/featured-problem")
    EngagementService.GroupFeatured featured(@AuthenticationPrincipal Jwt jwt, @PathVariable long groupId,
                                              @RequestParam(defaultValue = "live") String mode) {
        return standings.featured(groupId, actor(jwt), mode);
    }

    private static long actor(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    record Directory(List<GroupService.Summary> items, boolean demoAvailable) {}
    record Settings(@NotBlank @Size(max = 80) String name, @Size(max = 280) String description,
                    @Pattern(regexp = "PRIVATE|PUBLIC") String visibility) {
        Settings {
            description = description == null ? "" : description;
            visibility = visibility == null ? "PRIVATE" : visibility;
        }
    }
    record UpdatedSettings(@Size(max = 80) String name, @Size(max = 280) String description) {}
    record Join(@NotBlank @Size(max = 64) String code) {
        @Override
        public String toString() { return "Join[redacted]"; }
    }
    record InviteSettings(@NotNull @Min(1) @Max(168) Integer expiresInHours,
                          @NotNull @Min(1) @Max(100) Integer maxUses) {}
    record NewOwner(@NotNull @Pattern(regexp = "[1-9][0-9]{0,18}") String accountId) {
        NewOwner {
            if (accountId != null && accountId.matches("[1-9][0-9]{0,18}")) {
                try {
                    Long.parseLong(accountId);
                } catch (NumberFormatException overflow) {
                    throw new IllegalArgumentException("Account ID is out of range");
                }
            }
        }
    }
}
