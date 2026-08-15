package com.argus.auth.repository;

import com.argus.auth.model.PasskeyCredential;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PasskeyCredentialRepository extends JpaRepository<PasskeyCredential, Long> {

    Optional<PasskeyCredential> findByCredentialId(String credentialId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select credential from PasskeyCredential credential where credential.credentialId = :credentialId")
    Optional<PasskeyCredential> findLockedByCredentialId(@Param("credentialId") String credentialId);

    List<PasskeyCredential> findAllByUser_UsernameOrderByCreatedAtAsc(String username);

    Optional<PasskeyCredential> findByCredentialIdAndUser_Username(String credentialId, String username);
}
