package com.leetgrind.identity;

import com.leetgrind.platform.ApiErrors.Failure;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActiveAccountGuard {
    private final AccountRepository accounts;

    ActiveAccountGuard(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long accountId) {
        accounts.lockActiveAccount(accountId).orElseThrow(() ->
                new Failure(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue."));
    }
}
