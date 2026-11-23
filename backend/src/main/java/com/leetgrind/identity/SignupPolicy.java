package com.leetgrind.identity;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.leetgrind.platform.ApiErrors.Failure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class SignupPolicy {
    private enum Mode { OPEN, PILOT, CLOSED }
    private final Mode mode;
    private final Set<String> pilotEmails;

    SignupPolicy(@Value("${leetgrind.signup.mode}") Mode mode,
                 @Value("${leetgrind.signup.pilot-emails}") String allowed) {
        this.mode = mode;
        this.pilotEmails = Arrays.stream(allowed.split(",")).map(IdentityService::normalize)
                .filter(email -> !email.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    void check(String email) {
        if (mode == Mode.OPEN || (mode == Mode.PILOT && pilotEmails.contains(IdentityService.normalize(email)))) {
            return;
        }
        throw new Failure(HttpStatus.FORBIDDEN, "signup_unavailable", "Registration is unavailable for this email.");
    }
}
