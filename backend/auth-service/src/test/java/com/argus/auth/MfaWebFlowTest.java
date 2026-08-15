package com.argus.auth;

import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.UserAccountRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MfaWebFlowTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private UserAccountRepository users;

    @Test
    void enrollmentLoginReplayProtectionAndDisableWorkEndToEnd() throws Exception {
        Credentials credentials = registerAndToken("mfa-flow-user", "mfa-flow-password");
        MfaEnrollment enrollment = enroll(credentials, -1);

        UserAccount stored = users.findByUsername(credentials.username()).orElseThrow();
        assertTrue(stored.isMfaEnabled());
        assertNotEquals(enrollment.secret(), stored.getTotpSecretEncrypted());
        assertTrue(stored.getTotpSecretEncrypted().startsWith("v1.dev-v1."));

        JsonNode challenge = passwordLogin(credentials).path("challenge");
        String currentCode = totp(enrollment.secret(), currentCounter());
        JsonNode authenticated = verify(challenge.asText(), currentCode).path("body");
        assertTrue(authenticated.hasNonNull("token"));

        mvc.perform(post("/api/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("challengeToken", challenge.asText(), "method", "TOTP", "code", currentCode))))
                .andExpect(status().isUnauthorized());

        String bearer = "Bearer " + authenticated.path("token").asText();
        String nextCode = totp(enrollment.secret(), currentCounter() + 1);
        mvc.perform(post("/api/auth/mfa/totp/disable")
                        .header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", nextCode))))
                .andExpect(status().isOk());

        assertFalse(users.findByUsername(credentials.username()).orElseThrow().isMfaEnabled());
        assertTrue(passwordLogin(credentials).path("body").hasNonNull("token"));
    }

    @Test
    void fiveWrongCodesLockTheChallengeEvenWhenTheNextCodeIsCorrect() throws Exception {
        Credentials credentials = registerAndToken("mfa-lock-user", "mfa-lock-password");
        MfaEnrollment enrollment = enroll(credentials, -1);
        String challenge = passwordLogin(credentials).path("challenge").asText();
        String wrongCode = invalidTotp(enrollment.secret());

        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/api/auth/mfa/verify")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("challengeToken", challenge, "method", "TOTP", "code", wrongCode))))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("challengeToken", challenge, "method", "TOTP",
                                "code", totp(enrollment.secret(), currentCounter())))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryCodesAreOneTimeAndCanResetAForgottenPassword() throws Exception {
        Credentials credentials = registerAndToken("recovery-flow-user", "old-recovery-password");
        MfaEnrollment enrollment = enroll(credentials, -1);
        assertTrue(enrollment.recoveryCodes().size() == 10);
        assertTrue(enrollment.recoveryCodes().stream().distinct().count() == 10);
        assertTrue(enrollment.recoveryCodes().stream()
                .allMatch(code -> code.matches("[A-Z2-9]{4}(?:-[A-Z2-9]{4}){5}")));

        JsonNode firstChallenge = passwordLogin(credentials);
        assertTrue(firstChallenge.path("methods").toString().contains("RECOVERY_CODE"));
        verify(firstChallenge.path("challenge").asText(), enrollment.recoveryCodes().get(0), "RECOVERY_CODE");

        String pendingChallenge = passwordLogin(credentials).path("challenge").asText();
        mvc.perform(post("/api/auth/recovery/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", credentials.username(),
                                "recoveryCode", enrollment.recoveryCodes().get(1),
                                "newPassword", "new-recovery-password"))))
                .andExpect(status().isOk());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", credentials.username(), "password", credentials.password()))))
                .andExpect(status().isUnauthorized());
        JsonNode newChallenge = passwordLogin(new Credentials(
                credentials.username(), "new-recovery-password", ""));
        assertTrue(newChallenge.has("challenge"));

        mvc.perform(post("/api/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("challengeToken", pendingChallenge, "method", "RECOVERY_CODE",
                                "code", enrollment.recoveryCodes().get(2)))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/recovery/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "username", credentials.username(),
                                "recoveryCode", enrollment.recoveryCodes().get(1),
                                "newPassword", "another-password"))))
                .andExpect(status().isUnauthorized());
    }

    private MfaEnrollment enroll(Credentials credentials, long counterOffset) throws Exception {
        MvcResult setup = mvc.perform(post("/api/auth/mfa/totp/setup")
                        .header("Authorization", credentials.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode setupBody = mapper.readTree(setup.getResponse().getContentAsString());
        String secret = setupBody.path("secret").asText();
        assertTrue(setupBody.path("provisioningUri").asText().startsWith("otpauth://totp/"));

        String confirmationCode = totp(secret, currentCounter() + counterOffset);
        MvcResult confirmation = mvc.perform(post("/api/auth/mfa/totp/confirm")
                        .header("Authorization", credentials.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", confirmationCode))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode confirmationBody = mapper.readTree(confirmation.getResponse().getContentAsString());
        List<String> recoveryCodes = mapper.convertValue(
                confirmationBody.path("recoveryCodes"),
                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        return new MfaEnrollment(secret, recoveryCodes);
    }

    private JsonNode passwordLogin(Credentials credentials) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", credentials.username(), "password", credentials.password()))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        if (body.has("challengeToken")) {
            assertFalse(body.has("token"));
            return mapper.createObjectNode()
                    .put("challenge", body.path("challengeToken").asText())
                    .set("methods", body.path("methods"));
        }
        return mapper.createObjectNode().set("body", body);
    }

    private JsonNode verify(String challenge, String code) throws Exception {
        return verify(challenge, code, "TOTP");
    }

    private JsonNode verify(String challenge, String code, String method) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("challengeToken", challenge, "method", method, "code", code))))
                .andExpect(status().isOk())
                .andReturn();
        return mapper.createObjectNode().set("body", mapper.readTree(result.getResponse().getContentAsString()));
    }

    private Credentials registerAndToken(String username, String password) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isCreated());
        JsonNode token = passwordLogin(new Credentials(username, password, "")).path("body");
        return new Credentials(username, password, "Bearer " + token.path("token").asText());
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static long currentCounter() {
        return Instant.now().getEpochSecond() / 30;
    }

    private static String totp(String secret, long counter) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    }

    private static String invalidTotp(String secret) throws Exception {
        long counter = currentCounter();
        java.util.Set<String> valid = java.util.Set.of(
                totp(secret, counter - 1), totp(secret, counter), totp(secret, counter + 1));
        for (int candidate = 0; candidate < 1_000_000; candidate++) {
            String code = String.format(Locale.ROOT, "%06d", candidate);
            if (!valid.contains(code)) return code;
        }
        throw new IllegalStateException("No invalid TOTP code found");
    }

    private static byte[] decodeBase32(String input) {
        byte[] output = new byte[input.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (char character : input.toCharArray()) {
            int value = character >= 'A' && character <= 'Z'
                    ? character - 'A' : character - '2' + 26;
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                output[index++] = (byte) ((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return output;
    }

    private record Credentials(String username, String password, String bearer) {
    }

    private record MfaEnrollment(String secret, List<String> recoveryCodes) {
    }
}
