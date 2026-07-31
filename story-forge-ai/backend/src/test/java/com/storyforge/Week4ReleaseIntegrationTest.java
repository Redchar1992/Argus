package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("local")
@AutoConfigureMockMvc
@SpringBootTest
class Week4ReleaseIntegrationTest {
    private static final MockWebServer AI_SERVER = startAiServer();

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void aiServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> AI_SERVER.url("/").toString());
        registry.add("app.export.storage-dir", () -> "target/test-exports");
    }

    @BeforeEach
    void clean() throws InterruptedException {
        while (AI_SERVER.takeRequest(1, TimeUnit.MILLISECONDS) != null) { }
        jdbc.update("DELETE FROM user_feedback");
        jdbc.update("DELETE FROM user_ai_credit_log");
        jdbc.update("DELETE FROM user_ai_wallet");
        jdbc.update("DELETE FROM ai_model_usage");
        jdbc.update("DELETE FROM export_task");
        jdbc.update("DELETE FROM story_release");
        jdbc.update("DELETE FROM story_final_report");
        jdbc.update("DELETE FROM story_chapter_version");
        jdbc.update("DELETE FROM story_chapter");
        jdbc.update("DELETE FROM prompt_template");
        jdbc.update("DELETE FROM model_profile");
        jdbc.update("DELETE FROM ai_task");
        jdbc.update("DELETE FROM story_project");
        jdbc.update("DELETE FROM sys_user");
    }

    @AfterAll
    static void stop() throws IOException { AI_SERVER.shutdown(); }

    @Test
    void finalReviewOnlyUsesApprovedVersionsAndExportsLockedRelease() throws Exception {
        JsonNode user = register("week4-author");
        String token = user.path("token").asText();
        long userId = user.path("userId").asLong();
        long storyId = createStory(token).path("id").asLong();
        long chapterId = seedApprovedChapter(userId, storyId, 1, "第一章", "正文只能来自批准版本");

        AI_SERVER.enqueue(new MockResponse().setResponseCode(200)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("""
                        {"contentQuality":{"score":88,"summary":"完成度","strengths":["因果"],"weaknesses":[]},
                         "hitPotential":{"score":82,"summary":"潜力","strengths":["钩子"],"weaknesses":[]},
                         "shortDramaAdaptation":{"score":80,"summary":"适配","strengths":["场景"],"weaknesses":[]},
                         "criticalIssues":[],"normalIssues":[],"unresolvedThreads":[],"unresolvedForeshadowing":[],
                         "strongestChapters":[1],"weakestChapters":[1],"suggestedTitles":["正式标题"],"suggestedTags":["都市"],
                         "revisionOrder":[],"total":0,"level":"D","disclaimer":"测试报告不代表收益保证"}
                        """));

        MvcResult reportResult = mvc.perform(post("/api/stories/{storyId}/final-reviews", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(84)).andReturn();
        long reportId = mapper.readTree(reportResult.getResponse().getContentAsByteArray()).path("id").asLong();
        JsonNode aiRequest = mapper.readTree(AI_SERVER.takeRequest(1, TimeUnit.SECONDS).getBody().readUtf8());
        assertThat(aiRequest.path("chapters")).hasSize(1);
        assertThat(aiRequest.path("chapters").get(0).path("content").asText()).isEqualTo("正文只能来自批准版本");
        assertThat(jdbc.queryForObject("SELECT available_credits FROM user_ai_wallet WHERE user_id=?", Long.class, userId)).isEqualTo(70L);
        assertThat(chapterId).isPositive();

        MvcResult releaseResult = mvc.perform(post("/api/stories/{storyId}/releases", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode().put("reportId", reportId).toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("LOCKED")).andReturn();
        long releaseId = mapper.readTree(releaseResult.getResponse().getContentAsByteArray()).path("id").asLong();

        MvcResult exportResult = mvc.perform(post("/api/stories/{storyId}/exports", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.createObjectNode().put("releaseId", releaseId).put("format", "TXT").put("includeReport", true).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS")).andReturn();
        String downloadUrl = mapper.readTree(exportResult.getResponse().getContentAsByteArray()).path("downloadUrl").asText();
        MvcResult download = mvc.perform(get(downloadUrl)).andExpect(status().isOk()).andReturn();
        assertThat(download.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("正文只能来自批准版本")
                .contains("终审报告");
    }

    private JsonNode register(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode createStory(String token) throws Exception {
        MvcResult result = mvc.perform(post("/api/story/create").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"第四周测试\",\"genre\":\"都市情感\",\"audience\":\"女性\"}"))
                .andExpect(status().isCreated()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private long seedApprovedChapter(long userId, long storyId, int no, String title, String content) {
        jdbc.update("INSERT INTO story_chapter (story_id, chapter_no, title, status, plan_status, word_count, row_version, created_time, updated_time) VALUES (?, ?, ?, 'APPROVED', 'APPROVED', ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", storyId, no, title, content.length());
        long chapterId = jdbc.queryForObject("SELECT id FROM story_chapter WHERE story_id=? AND chapter_no=?", Long.class, storyId, no);
        String hash = sha(content);
        jdbc.update("INSERT INTO story_chapter_version (chapter_id, version_no, source_type, content, content_hash, idempotency_key, created_by, created_time) VALUES (?, 1, 'APPROVED', ?, ?, ?, ?, CURRENT_TIMESTAMP)", chapterId, content, hash, "week4-approved:" + chapterId, userId);
        long versionId = jdbc.queryForObject("SELECT id FROM story_chapter_version WHERE chapter_id=?", Long.class, chapterId);
        jdbc.update("UPDATE story_chapter SET current_version_id=?, approved_time=CURRENT_TIMESTAMP WHERE id=?", versionId, chapterId);
        return chapterId;
    }

    private String bearer(String token) { return "Bearer " + token; }
    private String sha(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static MockWebServer startAiServer() { try { MockWebServer server = new MockWebServer(); server.start(); return server; } catch (IOException exception) { throw new ExceptionInInitializerError(exception); } }
}
