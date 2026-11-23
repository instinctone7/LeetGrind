package com.leetgrind.integration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/leetcode-link")
class LinkController {
    private final LinkService links;
    private final ProfilePreview previews;

    LinkController(LinkService links, ProfilePreview previews) {
        this.links = links;
        this.previews = previews;
    }

    @GetMapping
    LinkService.Status status(@AuthenticationPrincipal Jwt jwt) {
        return links.status(accountId(jwt));
    }

    @GetMapping("/preview")
    ResponseEntity<ProfilePreview.Snapshot> preview(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(required = false) @Size(max = 256) String handle) {
        var snapshot = previews.read(accountId(jwt), handle);
        return snapshot == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(snapshot);
    }

    @PutMapping
    LinkService.Status link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkRequest request) {
        return links.link(accountId(jwt), request.handle());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disconnect(@AuthenticationPrincipal Jwt jwt) {
        links.disconnect(accountId(jwt));
    }

    @PostMapping("/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    LinkService.Status requestSync(@AuthenticationPrincipal Jwt jwt) {
        return links.requestSync(accountId(jwt));
    }

    private static long accountId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    record LinkRequest(@NotBlank @Size(max = 256) String handle) {}
}
