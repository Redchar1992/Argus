package com.argus.auth.repository;

import com.argus.auth.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    Optional<UserAccount> findByUsername(String username);

    Optional<UserAccount> findByOidcIssuerAndOidcSubject(String oidcIssuer, String oidcSubject);

    boolean existsByUsername(String username);
}
