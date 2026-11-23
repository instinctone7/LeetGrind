package com.leetgrind.identity;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface RefreshSessionRepository extends JpaRepository<RefreshSession, String> {
    @Query("select s.family.account.id from RefreshSession s where s.digest = :digest")
    Optional<Long> findAccountId(String digest);

    @Query("select s.family.id from RefreshSession s where s.digest = :digest")
    Optional<String> findFamilyId(String digest);
}
