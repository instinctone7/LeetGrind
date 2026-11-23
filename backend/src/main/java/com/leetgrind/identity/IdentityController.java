package com.leetgrind.identity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import com.leetgrind.identity.IdentityService.Credentials;
import com.leetgrind.identity.IdentityService.Profile;
import com.leetgrind.platform.ApiErrors.Failure;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class IdentityController {
    private final IdentityService identity;
    private final AccountDeletion deletion;
    private final AuthProperties properties;

    IdentityController(IdentityService identity, AccountDeletion deletion, AuthProperties properties) {
        this.identity = identity;
        this.deletion = deletion;
        this.properties = properties;
    }

    @PostMapping(path = "/auth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    Profile register(@Valid @RequestBody Registration request) {
        validatePassword(request.password(), true);
        return identity.register(request.email(), request.displayName(), request.password(), request.leetcodeHandle());
    }

    @PostMapping(path = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    AccessToken login(@Valid @RequestBody Login request, HttpServletResponse response) {
        validatePassword(request.password(), false);
        Credentials credentials = identity.login(request.email(), request.password());
        if (credentials == null) {
            throw new Failure(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect.");
        }
        return sessionResponse(credentials, response);
    }

    @PostMapping("/auth/refresh")
    AccessToken refresh(HttpServletRequest request, HttpServletResponse response) {
        Credentials credentials = identity.refresh(refreshCookie(request));
        if (credentials == null) {
            setRefreshCookie(response, "", 0);
            throw new Failure(HttpStatus.UNAUTHORIZED, "invalid_session", "Your session has ended. Sign in again.");
        }
        return sessionResponse(credentials, response);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response) {
        identity.logout(refreshCookie(request));
        setRefreshCookie(response, "", 0);
    }

    @GetMapping("/me")
    Profile profile(@AuthenticationPrincipal Jwt jwt) {
        return activeProfile(identity.getProfile(Long.parseLong(jwt.getSubject())));
    }

    @PatchMapping(path = "/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    Profile update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileUpdate request) {
        return activeProfile(identity.updateProfile(Long.parseLong(jwt.getSubject()), request.displayName()));
    }

    @PostMapping(path = "/me/delete", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Deletion request, HttpServletResponse response) {
        validatePassword(request.password(), false);
        deletion.erase(Long.parseLong(jwt.getSubject()), request.password());
        setRefreshCookie(response, "", 0);
    }

    private static Profile activeProfile(Profile profile) {
        if (profile == null) {
            throw new Failure(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue.");
        }
        return profile;
    }

    private AccessToken sessionResponse(Credentials credentials, HttpServletResponse response) {
        setRefreshCookie(response, credentials.refreshToken(), credentials.refreshSeconds());
        return new AccessToken(credentials.accountId(), credentials.accessToken(), credentials.accessSeconds());
    }

    private String refreshCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        var matching = Arrays.stream(cookies).filter(cookie -> properties.cookieName().equals(cookie.getName())).toList();
        return matching.size() == 1 ? matching.getFirst().getValue() : null;
    }

    private void setRefreshCookie(HttpServletResponse response, String value, long maxAge) {
        var cookie = ResponseCookie.from(properties.cookieName(), value)
                .path(properties.secureCookie() ? "/" : "/api/v1/auth")
                .httpOnly(true)
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .maxAge(Duration.ofSeconds(maxAge))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private static void validatePassword(String password, boolean registration) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72
                || (registration && password.codePointCount(0, password.length()) < 12)) {
            throw new Failure(HttpStatus.BAD_REQUEST, "invalid_password",
                    "Use at least 12 characters and no more than 72 UTF-8 bytes.");
        }
    }

    record Registration(@NotBlank @Email @Size(max = 254) String email,
                        @NotBlank @Size(max = 80) String displayName,
                        @NotBlank @Size(max = 72) String password,
                        @NotBlank @Size(max = 256) String leetcodeHandle) {
        Registration {
            email = email == null ? null : email.strip();
        }

        @Override
        public String toString() { return "Registration[redacted]"; }
    }

    record Login(@NotBlank @Email @Size(max = 254) String email,
                 @NotBlank @Size(max = 72) String password) {
        Login {
            email = email == null ? null : email.strip();
        }

        @Override
        public String toString() { return "Login[redacted]"; }
    }

    record Deletion(@NotBlank @Size(max = 72) String password) {
        @Override
        public String toString() { return "Deletion[redacted]"; }
    }

    record ProfileUpdate(@NotBlank @Size(max = 80) String displayName) {}
    record AccessToken(String accountId, String accessToken, long expiresIn) {
        @Override
        public String toString() { return "AccessToken[redacted]"; }
    }
}
