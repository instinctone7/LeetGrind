package com.leetgrind.identity;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

interface SessionFamilyRepository extends JpaRepository<SessionFamily, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from SessionFamily f where f.id = :id")
    Optional<SessionFamily> lock(String id);

    @Query("""
            select count(f) > 0 from SessionFamily f
            where f.id = :familyId and f.account.id = :accountId and f.account.active = true
              and f.revokedAt is null and f.expiresAt > :now
            """)
    boolean isActive(String familyId, long accountId, Instant now);
}
