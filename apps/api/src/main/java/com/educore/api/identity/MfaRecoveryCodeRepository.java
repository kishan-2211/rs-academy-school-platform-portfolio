package com.educore.api.identity;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MfaRecoveryCodeRepository extends JpaRepository<MfaRecoveryCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MfaRecoveryCode> findByUserIdAndCodeHashAndUsedAtIsNullAndRevokedAtIsNull(UUID userId, String codeHash);

    long countByUserIdAndUsedAtIsNullAndRevokedAtIsNull(UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select code from MfaRecoveryCode code where code.userId = :userId and code.usedAt is null and code.revokedAt is null order by code.id")
    List<MfaRecoveryCode> findAvailableForUpdate(@Param("userId") UUID userId);
}
