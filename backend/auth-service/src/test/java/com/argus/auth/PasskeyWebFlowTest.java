package com.argus.auth;

import com.argus.auth.security.InternalBffAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PasskeyWebFlowTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void registrationMaterialCounterRaceAndOwnershipAreEnforced() throws Exception {
        String ownerToken = registerAndToken("passkey-owner", "passkey-owner-password");
        String otherToken = registerAndToken("passkey-other", "passkey-other-password");
        String credentialId = randomBase64Url(32);
        String publicKey = randomBase64Url(77);

        mvc.perform(get("/api/auth/passkeys/context").header("Authorization", ownerToken))
                .andExpect(status().isUnauthorized());
        MvcResult context = mvc.perform(get("/api/auth/passkeys/context")
                        .header("Authorization", ownerToken)
                        .header("X-Argus-Bff-Secret", InternalBffAuth.DEV_SECRET))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode contextBody = mapper.readTree(context.getResponse().getContentAsString());
        assertTrue(contextBody.path("userId").asLong() > 0);
        assertEquals(0, contextBody.path("credentials").size());

        Map<String, Object> registration = Map.of(
                "credentialId", credentialId,
                "publicKey", publicKey,
                "counter", 0,
                "transports", List.of("internal", "hybrid"),
                "deviceType", "multiDevice",
                "backedUp", true,
                "aaguid", "00000000-0000-0000-0000-000000000000",
                "label", "MacBook passkey");
        mvc.perform(post("/api/auth/passkeys")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(registration)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/passkeys")
                        .header("Authorization", ownerToken)
                        .header("X-Argus-Bff-Secret", InternalBffAuth.DEV_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(registration)))
                .andExpect(status().isCreated());

        MvcResult listed = mvc.perform(get("/api/auth/passkeys").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = mapper.readTree(listed.getResponse().getContentAsString());
        assertEquals("MacBook passkey", list.path(0).path("label").asText());
        assertFalse(list.path(0).has("publicKey"));

        MvcResult material = mvc.perform(get("/api/auth/internal/passkeys/{id}", credentialId)
                        .header("X-Argus-Bff-Secret", InternalBffAuth.DEV_SECRET))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(publicKey, mapper.readTree(material.getResponse().getContentAsString())
                .path("publicKey").asText());

        Map<String, Object> completion = Map.of(
                "credentialId", credentialId,
                "expectedCounter", 0,
                "newCounter", 1,
                "deviceType", "multiDevice",
                "backedUp", true);
        mvc.perform(post("/api/auth/internal/passkeys/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(completion)))
                .andExpect(status().isUnauthorized());
        MvcResult authenticated = mvc.perform(post("/api/auth/internal/passkeys/complete")
                        .header("X-Argus-Bff-Secret", InternalBffAuth.DEV_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(completion)))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(mapper.readTree(authenticated.getResponse().getContentAsString()).hasNonNull("token"));

        mvc.perform(post("/api/auth/internal/passkeys/complete")
                        .header("X-Argus-Bff-Secret", InternalBffAuth.DEV_SECRET)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "credentialId", credentialId, "expectedCounter", 0, "newCounter", 2,
                                "deviceType", "multiDevice", "backedUp", true))))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/auth/passkeys/{id}", credentialId).header("Authorization", otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/auth/passkeys/{id}", credentialId).header("Authorization", ownerToken))
                .andExpect(status().isNoContent());
    }

    private String registerAndToken(String username, String password) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isCreated());
        MvcResult login = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return "Bearer " + mapper.readTree(login.getResponse().getContentAsString()).path("token").asText();
    }

    private String json(Object value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static String randomBase64Url(int size) {
        byte[] value = new byte[size];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
