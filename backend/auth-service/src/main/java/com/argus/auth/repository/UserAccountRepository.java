package com.argus.auth.repository;

import com.argus.auth.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);

    boolean existsByUsername(String username);

    @Query("""
            select u from UserAccount u
            where (u.totpSecretEncrypted is not null and u.totpSecretEncrypted not like :primaryPrefix)
               or (u.pendingTotpSecretEncrypted is not null
                   and u.pendingTotpSecretEncrypted not like :primaryPrefix)
            """)
    List<UserAccount> findIdentitySecretsNeedingRotation(
            @Param("primaryPrefix") String primaryPrefix,
            Pageable pageable);
}
