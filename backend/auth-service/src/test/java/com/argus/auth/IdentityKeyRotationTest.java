package com.argus.auth;

import com.argus.auth.dto.AuthDtos.IdentityKeyRotationResponse;
import com.argus.auth.model.Role;
import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.IdentitySecretCipher;
import com.argus.auth.service.MfaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:identitykeyrotationdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
        "argus.identity.primary-key-id=new-v2",
        "argus.identity.keys=old-v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=,"
                + "new-v2:BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
})
class IdentityKeyRotationTest {

    @Autowired
    private UserAccountRepository users;
    @Autowired
    private MfaService mfaService;
    @Autowired
    private IdentitySecretCipher currentCipher;

    @Test
    void boundedRotationPersistsActiveAndPendingSecretsUnderPrimaryKey() {
        IdentitySecretCipher oldCipher = new IdentitySecretCipher(
                "old-v1",
                "old-v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                new MockEnvironment());
        UserAccount user = new UserAccount("key-rotation-user", "unused-hash", Role.ANALYST);
        user.beginTotpEnrollment(oldCipher.encrypt("ACTIVE-SEED"), Instant.now().plusSeconds(600));
        user.confirmTotpEnrollment(1, Instant.now());
        user.beginTotpEnrollment(oldCipher.encrypt("PENDING-SEED"), Instant.now().plusSeconds(600));
        users.saveAndFlush(user);

        IdentityKeyRotationResponse first = mfaService.rotateIdentitySecrets(100);
        UserAccount rotated = users.findByUsername("key-rotation-user").orElseThrow();

        assertEquals("new-v2", first.primaryKeyId());
        assertEquals(1, first.rotated());
        assertTrue(rotated.getTotpSecretEncrypted().startsWith("v1.new-v2."));
        assertTrue(rotated.getPendingTotpSecretEncrypted().startsWith("v1.new-v2."));
        assertEquals("ACTIVE-SEED", currentCipher.decrypt(rotated.getTotpSecretEncrypted()));
        assertEquals("PENDING-SEED", currentCipher.decrypt(rotated.getPendingTotpSecretEncrypted()));

        IdentityKeyRotationResponse second = mfaService.rotateIdentitySecrets(100);
        assertEquals(0, second.scanned());
        assertEquals(0, second.rotated());
    }
}
