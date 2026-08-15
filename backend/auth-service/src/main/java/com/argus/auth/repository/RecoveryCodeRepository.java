package com.argus.auth.repository;

import com.argus.auth.model.RecoveryCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface RecoveryCodeRepository extends JpaRepository<RecoveryCode, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecoveryCode> findByUser_IdAndCodeHash(Long userId, String codeHash);

    long countByUser_IdAndUsedAtIsNull(Long userId);

    long deleteByUser_Id(Long userId);
}
