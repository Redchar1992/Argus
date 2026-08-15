package com.argus.auth;

import com.argus.auth.model.Role;
import com.argus.auth.model.UserAccount;
import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.OidcTokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class OidcLoginWebTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private UserAccountRepository repository;

    @MockitoBean
    private OidcTokenVerifier verifier;

    @Test
    void verifiedIssuerSubjectIsProvisionedOnceAndGetsArgusToken() throws Exception {
        when(verifier.verify("valid-id-token", "expected-nonce")).thenReturn(
                new OidcTokenVerifier.OidcIdentity(
                        "https://issuer.example", "provider-subject-1", "analyst@example.com"));

        long before = repository.count();
        MvcResult first = login("valid-id-token", "expected-nonce")
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = mapper.readTree(first.getResponse().getContentAsString());
        assertEquals("Bearer", body.path("tokenType").asText());
        assertEquals("ANALYST", body.path("role").asText());
        String username = body.path("username").asText();

        UserAccount stored = repository.findByOidcIssuerAndOidcSubject(
                "https://issuer.example", "provider-subject-1").orElseThrow();
        assertEquals(username, stored.getUsername());
        assertEquals("analyst@example.com", stored.getEmail());
        assertNull(stored.getPasswordHash());

        login("valid-id-token", "expected-nonce").andExpect(status().isOk());
        assertEquals(before + 1, repository.count());
    }

    @Test
    void matchingEmailNeverLinksIdentitiesAcrossIssuerSubjectPairs() throws Exception {
        UserAccount existing = repository.save(UserAccount.oidc(
                "existing-external-user", "https://issuer-a.example", "subject-a",
                "shared@example.com", Role.ANALYST));
        when(verifier.verify("second-id-token", "second-nonce")).thenReturn(
                new OidcTokenVerifier.OidcIdentity(
                        "https://issuer-b.example", "subject-b", "shared@example.com"));

        MvcResult result = login("second-id-token", "second-nonce")
                .andExpect(status().isOk())
                .andReturn();
        String newUsername = mapper.readTree(result.getResponse().getContentAsString())
                .path("username").asText();

        UserAccount provisioned = repository.findByOidcIssuerAndOidcSubject(
                "https://issuer-b.example", "subject-b").orElseThrow();
        assertNotEquals(existing.getId(), provisioned.getId());
        assertEquals(newUsername, provisioned.getUsername());
    }

    @Test
    void verifierFailureIsRejectedWithoutProvisioning() throws Exception {
        when(verifier.verify("bad-id-token", "bad-nonce"))
                .thenThrow(new ResponseStatusException(UNAUTHORIZED, "Invalid OIDC identity token"));
        long before = repository.count();

        login("bad-id-token", "bad-nonce").andExpect(status().isUnauthorized());
        assertEquals(before, repository.count());
    }

    private org.springframework.test.web.servlet.ResultActions login(String token, String nonce) throws Exception {
        return mvc.perform(post("/api/auth/oidc/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("idToken", token, "nonce", nonce))));
    }
}
