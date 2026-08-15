package com.argus.auth.repository;

import com.argus.auth.model.AuthenticationChallenge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.Instant;
import java.util.Optional;

public interface AuthenticationChallengeRepository extends JpaRepository<AuthenticationChallenge, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthenticationChallenge> findByTokenHash(String tokenHash);

    long deleteByExpiresAtBefore(Instant cutoff);
}
