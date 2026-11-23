package com.leetgrind.identity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "refresh_sessions")
class RefreshSession {
    @Id
    @Column(length = 64)
    private String digest;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "family_id", nullable = false)
    private SessionFamily family;
    @Column(name = "used_at")
    private Instant usedAt;

    protected RefreshSession() {}

    RefreshSession(String digest, SessionFamily family) {
        this.digest = digest;
        this.family = family;
    }

    boolean used() { return usedAt != null; }

    void use(Instant now) {
        usedAt = now;
    }
}
