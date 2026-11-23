package com.leetgrind.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "accounts")
class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 254)
    private String email;
    @Column(nullable = false, length = 32)
    private String handle;
    @Column(name = "display_name", nullable = false, length = 80)
    private String displayName;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private boolean active;

    protected Account() {}

    Account(String email, String handle, String displayName, String passwordHash) {
        this.email = email;
        this.handle = handle;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.active = true;
    }

    long id() { return id; }
    String email() { return email; }
    String handle() { return handle; }
    String displayName() { return displayName; }
    String passwordHash() { return passwordHash; }
    boolean active() { return active; }

    void rename(String displayName) {
        this.displayName = displayName;
    }
}
