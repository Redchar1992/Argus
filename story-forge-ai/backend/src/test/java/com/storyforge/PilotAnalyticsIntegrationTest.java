package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.analytics.ProductAnalyticsService;
import com.storyforge.analytics.ProductEventNames;
import com.storyforge.common.privacy.PrivacyPolicy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("local")
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource(properties = "app.pilot.metrics-key=test-pilot-metrics-key-32-bytes")
class PilotAnalyticsIntegrationTest {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProductAnalyticsService analytics;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM product_event");
        jdbc.update("DELETE FROM user_feedback");
        jdbc.update("DELETE FROM export_task");
        jdbc.update("DELETE FROM story_release");
        jdbc.update("DELETE FROM story_final_report");
        jdbc.update("DELETE FROM ai_model_usage");
        jdbc.update("DELETE FROM user_ai_credit_log");
        jdbc.update("DELETE FROM user_ai_wallet");
        jdbc.update("DELETE FROM prompt_template");
        jdbc.update("DELETE FROM model_profile");
        jdbc.update("DELETE FROM ai_task_event");
        jdbc.update("DELETE FROM story_rewrite_proposal");
        jdbc.update("DELETE FROM story_chapter_summary");
        jdbc.update("UPDATE story_chapter SET current_version_id=NULL");
        jdbc.update("DELETE FROM story_chapter_version");
        jdbc.update("DELETE FROM story_fact");
        jdbc.update("DELETE FROM story_relationship");
        jdbc.update("DELETE FROM story_plot_thread");
        jdbc.update("DELETE FROM story_foreshadowing");
        jdbc.update("DELETE FROM story_artifact");
        jdbc.update("DELETE FROM ai_task");
        jdbc.update("DELETE FROM story_chapter");
        jdbc.update("DELETE FROM story_project");
        jdbc.update("DELETE FROM sys_user");
    }

    @Test
    void registrationRequiresPrivacyConsentAndPersistsItsVersion() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"no-consent","password":"password123"}
                                """))
                .andExpect(status().isBadRequest());

        JsonNode registration = register("pilot-author");
        Long userId = registration.path("userId").asLong();

        assertThat(jdbc.queryForObject(
                "SELECT privacy_version FROM sys_user WHERE id=?",
                String.class,
                userId
        )).isEqualTo(PrivacyPolicy.CURRENT_VERSION);
        assertThat(jdbc.queryForObject(
                "SELECT privacy_accepted_time IS NOT NULL FROM sys_user WHERE id=?",
                Boolean.class,
                userId
        )).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_event WHERE event_name='USER_REGISTERED' AND user_id=?",
                Long.class,
                userId
        )).isEqualTo(1L);
    }

    @Test
    void legacyUserMustAcceptCurrentPrivacyNoticeBeforeAnalyticsResume()
            throws Exception {
        JsonNode registration = register("legacy-author");
        long userId = registration.path("userId").asLong();
        jdbc.update("DELETE FROM product_event WHERE user_id=?", userId);
        jdbc.update("""
                UPDATE sys_user
                SET privacy_version=NULL, privacy_accepted_time=NULL
                WHERE id=?
                """, userId);

        analytics.record(
                ProductEventNames.USER_LOGIN_DAILY,
                userId,
                null,
                null,
                "legacy-login-without-consent"
        );
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_event WHERE user_id=?",
                Long.class,
                userId
        )).isZero();

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"legacy-author","password":"password123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVACY_CONSENT_REQUIRED"));

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"legacy-author","password":"password123",
                                 "privacyAccepted":true}
                                """))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject(
                "SELECT privacy_version FROM sys_user WHERE id=?",
                String.class,
                userId
        )).isEqualTo(PrivacyPolicy.CURRENT_VERSION);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_event WHERE user_id=? AND event_name='USER_LOGIN_DAILY'",
                Long.class,
                userId
        )).isEqualTo(1L);
    }

    @Test
    void metricsAreServerAuthoritativeIdempotentAndKeyProtected() throws Exception {
        JsonNode registration = register("metrics-author");
        long userId = registration.path("userId").asLong();
        String token = registration.path("token").asText();
        JsonNode story = mapper.readTree(mvc.perform(post("/api/story/create")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"内测故事","genre":"都市情感","audience":"女性"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
        long storyId = story.path("id").asLong();

        analytics.record(
                ProductEventNames.STORY_CREATED,
                userId,
                storyId,
                null,
                "story:" + storyId + ":created"
        );
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_event WHERE idempotency_key=?",
                Long.class,
                "story:" + storyId + ":created"
        )).isEqualTo(1L);

        mvc.perform(get("/api/internal/pilot/metrics")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("PILOT_METRICS_UNAUTHORIZED"));

        mvc.perform(get("/api/internal/pilot/metrics")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header("X-Pilot-Metrics-Key", "test-pilot-metrics-key-32-bytes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(7))
                .andExpect(jsonPath("$.registeredUsers").value(1))
                .andExpect(jsonPath("$.activeUsers").value(1))
                .andExpect(jsonPath("$.funnel[0].eventName").value("USER_REGISTERED"))
                .andExpect(jsonPath("$.funnel[0].users").value(1))
                .andExpect(jsonPath("$.funnel[1].eventName").value("STORY_CREATED"))
                .andExpect(jsonPath("$.funnel[1].stories").value(1));
    }

    private JsonNode register(String username) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"password123","privacyAccepted":true}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
