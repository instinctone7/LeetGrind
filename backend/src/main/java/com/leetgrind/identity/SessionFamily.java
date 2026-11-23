package com.leetgrind.identity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "session_families")
class SessionFamily {
    @Id
    @Column(length = 36)
    private String id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected SessionFamily() {}

    SessionFamily(Account account, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.account = account;
        this.expiresAt = expiresAt;
    }

    String id() { return id; }
    Account account() { return account; }
    Instant expiresAt() { return expiresAt; }

    boolean activeAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
