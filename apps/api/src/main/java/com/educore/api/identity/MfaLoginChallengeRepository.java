package com.educore.api.identity;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MfaLoginChallengeRepository extends JpaRepository<MfaLoginChallenge, UUID> {

    @Query("select challenge.userId from MfaLoginChallenge challenge where challenge.tokenHash = :tokenHash")
    Optional<UUID> findOwnerByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MfaLoginChallenge> findByTokenHash(String tokenHash);

    long countByUserIdAndConsumedAtIsNullAndExpiresAtAfter(UUID userId, Instant now);
}
