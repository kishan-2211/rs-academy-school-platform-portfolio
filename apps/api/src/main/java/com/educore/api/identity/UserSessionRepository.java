package com.educore.api.identity;

import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    @EntityGraph(attributePaths = "user")
    Optional<UserSession> findByTokenHash(String tokenHash);

    List<UserSession> findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(UUID userId);

    @EntityGraph(attributePaths = "user")
    Optional<UserSession> findByIdAndUserId(UUID id, UUID userId);
}
