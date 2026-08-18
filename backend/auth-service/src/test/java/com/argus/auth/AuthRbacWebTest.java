package com.argus.auth;

import com.argus.auth.repository.UserAccountRepository;
import com.argus.auth.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end web-layer proof for the privilege-escalation fix (issue #1) and that
 * the admin-only role-assignment endpoint enforces RBAC. Drives the real security
 * filter chain via MockMvc so {@code @PreAuthorize} and the JWT filter are exercised.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthRbacWebTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserAccountRepository repository;
    @Autowired
    private ObjectMapper mapper;

    private String bearerFor(String username) {
        return "Bearer " + jwtService.issue(repository.findByUsername(username).orElseThrow());
    }

    @Test
    void registerWithRoleAdminInBodyDoesNotCreateAdmin() throws Exception {
        // A malicious payload tries to smuggle role=ADMIN. The DTO ignores unknown
        // fields and the service hardcodes ANALYST, so the created user is NOT admin.
        MvcResult res = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sneaky\",\"password\":\"sneakypass1\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = mapper.readTree(res.getResponse().getContentAsString());
        assertEquals("ANALYST", body.path("role").asText());
        assertEquals("ANALYST", repository.findByUsername("sneaky").orElseThrow().getRole().name());
    }

    @Test
    void roleAssignmentRejectsNonAdminToken() throws Exception {
        // 'analyst' is a seeded non-admin. Its token must NOT be allowed to elevate anyone.
        mvc.perform(put("/api/auth/users/analyst/role")
                        .header("Authorization", bearerFor("analyst"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleAssignmentRequiresAuthentication() throws Exception {
        mvc.perform(put("/api/auth/users/analyst/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminTokenCanAssignRole() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"tobepromoted\",\"password\":\"promote-pass1\"}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/auth/users/tobepromoted/role")
                        .header("Authorization", bearerFor("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk());

        assertEquals("ADMIN", repository.findByUsername("tobepromoted").orElseThrow().getRole().name());
    }

    @Test
    void identityKeyRotationIsAdminOnly() throws Exception {
        mvc.perform(post("/api/auth/admin/identity-keys/rotate"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/admin/identity-keys/rotate")
                        .header("Authorization", bearerFor("analyst")))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/admin/identity-keys/rotate")
                        .header("Authorization", bearerFor("admin")))
                .andExpect(status().isOk());
    }

    @Test
    void publicJwksIsAnonymousCacheableAndContainsNoPrivateExponent() throws Exception {
        MvcResult result = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andExpect(jsonPath("$.keys[0].kid").value("demo-auth-v1"))
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals(false, body.path("keys").get(0).has("d"));
    }
}
