package com.argus.auth.config;

import com.argus.auth.model.Role;
import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.UserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Seeds two demo accounts on first boot if the store is empty, using REAL bcrypt
 * hashing (not pre-baked strings). Credentials are documented in the README and
 * are intended for local demo only.
 */
@Configuration
public class UserSeeder {

    private static final Logger log = LoggerFactory.getLogger(UserSeeder.class);

    @Bean
    public ApplicationRunner seedUsers(UserAccountRepository repository, PasswordEncoder encoder) {
        return args -> {
            if (repository.count() > 0) {
                return;
            }
            repository.save(new UserAccount("admin", encoder.encode("admin12345"), Role.ADMIN));
            repository.save(new UserAccount("analyst", encoder.encode("analyst12345"), Role.ANALYST));
            log.info("Seeded demo users: admin/admin12345 (ADMIN), analyst/analyst12345 (ANALYST)");
        };
    }
}
