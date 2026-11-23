package com.leetgrind.identity;

import java.time.Clock;

import com.leetgrind.groups.GroupService;
import com.leetgrind.integration.LinkService;
import com.leetgrind.platform.ApiErrors.Failure;
import com.leetgrind.platform.FixedWindowRateLimiter;
import com.leetgrind.progress.ProgressService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountDeletion {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final GroupService groups;
    private final ProgressService progress;
    private final LinkService links;
    private final JdbcClient jdbc;
    private final FixedWindowRateLimiter attempts;

    AccountDeletion(AccountRepository accounts, PasswordEncoder passwords, GroupService groups, ProgressService progress,
                    LinkService links, JdbcClient jdbc, Clock clock) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.groups = groups;
        this.progress = progress;
        this.links = links;
        this.jdbc = jdbc;
        this.attempts = new FixedWindowRateLimiter(clock, 10);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void erase(long accountId, String password) {
        if (!attempts.allow(Long.toString(accountId))) {
            throw new Failure(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many attempts. Try again in a minute.");
        }
        Account account = accounts.lockActiveAccount(accountId).orElseThrow(() ->
                new Failure(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."));
        if (!passwords.matches(password, account.passwordHash())) {
            throw new Failure(HttpStatus.FORBIDDEN, "incorrect_password", "The current password is incorrect.");
        }

        groups.eraseMemberships(accountId);
        progress.eraseCredits(accountId);
        links.eraseActivity(accountId);
        jdbc.sql("""
                DELETE r FROM refresh_sessions r JOIN session_families f ON r.family_id = f.id
                WHERE f.account_id = ?
                """).param(accountId).update();
        jdbc.sql("DELETE FROM session_families WHERE account_id = ?").param(accountId).update();
        accounts.delete(account);
    }
}
