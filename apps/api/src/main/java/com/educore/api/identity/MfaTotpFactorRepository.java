package com.educore.api.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

interface MfaTotpFactorRepository extends JpaRepository<MfaTotpFactor, UUID> {

    List<MfaTotpFactor> findByUserIdAndStatusOrderByCreatedAtAsc(UUID userId, MfaFactorStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MfaTotpFactor> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select factor from MfaTotpFactor factor where factor.userId = :userId and factor.status = :status order by factor.createdAt")
    List<MfaTotpFactor> findForUpdate(
            @Param("userId") UUID userId,
            @Param("status") MfaFactorStatus status);

    long countByUserIdAndStatus(UUID userId, MfaFactorStatus status);

}
