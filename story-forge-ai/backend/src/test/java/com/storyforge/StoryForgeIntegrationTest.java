package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
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
class StoryForgeIntegrationTest {

    private static final MockWebServer AI_SERVER = startAiServer();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void aiServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai.base-url", () -> AI_SERVER.url("/").toString());
    }

    @BeforeEach
    void cleanDatabase() throws InterruptedException {
        while (AI_SERVER.takeRequest(1, TimeUnit.MILLISECONDS) != null) {
            // MockWebServer keeps recorded requests across test methods.
        }
        jdbcTemplate.update("DELETE FROM product_event");
        jdbcTemplate.update("DELETE FROM user_feedback");
        jdbcTemplate.update("DELETE FROM export_task");
        jdbcTemplate.update("DELETE FROM story_release");
        jdbcTemplate.update("DELETE FROM story_final_report");
        jdbcTemplate.update("DELETE FROM ai_model_usage");
        jdbcTemplate.update("DELETE FROM user_ai_credit_log");
        jdbcTemplate.update("DELETE FROM user_ai_wallet");
        jdbcTemplate.update("DELETE FROM prompt_template");
        jdbcTemplate.update("DELETE FROM model_profile");
        jdbcTemplate.update("DELETE FROM ai_task_event");
        jdbcTemplate.update("DELETE FROM story_rewrite_proposal");
        jdbcTemplate.update("DELETE FROM story_chapter_summary");
        jdbcTemplate.update("UPDATE story_chapter SET current_version_id=NULL");
        jdbcTemplate.update("DELETE FROM story_chapter_version");
        jdbcTemplate.update("DELETE FROM story_fact");
        jdbcTemplate.update("DELETE FROM story_relationship");
        jdbcTemplate.update("DELETE FROM story_plot_thread");
        jdbcTemplate.update("DELETE FROM story_foreshadowing");
        jdbcTemplate.update("DELETE FROM story_artifact");
        jdbcTemplate.update("DELETE FROM ai_task");
        jdbcTemplate.update("DELETE FROM story_chapter");
        jdbcTemplate.update("DELETE FROM story_project");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    @AfterAll
    static void stopAiServer() throws IOException {
        AI_SERVER.shutdown();
    }

    @Test
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void registerAndLoginReturnJwtAndUseBcrypt() throws Exception {
        JsonNode registration = register("writer");

        assertThat(registration.path("token").asText()).isNotBlank();
        assertThat(registration.path("userId").asLong()).isPositive();

        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password FROM sys_user WHERE username = ?",
                String.class,
                "writer"
        );
        assertThat(passwordHash).startsWith("$2");
        assertThat(passwordHash).doesNotContain("password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"writer","password":"password123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(registration.path("userId").asLong()));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"writer","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/story/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void usersCannotReadOrGenerateForOtherUsersStory() throws Exception {
        int requestCountBefore = AI_SERVER.getRequestCount();
        String firstToken = register("first-user").path("token").asText();
        String secondToken = register("second-user").path("token").asText();
        long storyId = createStory(firstToken, "错位人生", "都市情感", null, null).path("id").asLong();

        mockMvc.perform(get("/api/story/{id}", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(secondToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORY_FORBIDDEN"));

        mockMvc.perform(post("/api/ai/topic/generate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(secondToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storyId": %d,
                                  "audience": "女性",
                                  "keywords": "复仇"
                                }
                                """.formatted(storyId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORY_FORBIDDEN"));

        assertThat(AI_SERVER.getRequestCount()).isEqualTo(requestCountBefore);
    }

    @Test
    void completeTopicGenerationFlowPersistsAndCanBeViewedAndSelected() throws Exception {
        String token = register("happy-writer").path("token").asText();
        JsonNode story = createStory(token, "离婚之后", "都市情感", "女性", "复仇");
        long storyId = story.path("id").asLong();

        AI_SERVER.enqueue(jsonResponse(200, validAiResponse()));

        mockMvc.perform(post("/api/ai/topic/generate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storyId": %d,
                                  "genre": "都市情感",
                                  "audience": "女性",
                                  "keywords": "复仇"
                                }
                                """.formatted(storyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storyId").value(storyId))
                .andExpect(jsonPath("$.taskId").isNumber())
                .andExpect(jsonPath("$.generatedAt").value("2026-07-30T12:00:00Z"))
                .andExpect(jsonPath("$.topics[0].id").value(1))
                .andExpect(jsonPath("$.topics[0].score").value(92))
                .andExpect(jsonPath("$.topics[0].scoreReasons.conflict.score").value(92))
                .andExpect(jsonPath("$.topics.length()").value(10));

        RecordedRequest aiRequest = AI_SERVER.takeRequest(1, TimeUnit.SECONDS);
        assertThat(aiRequest).isNotNull();
        assertThat(aiRequest.getPath()).isEqualTo("/ai/topic/generate");
        JsonNode aiRequestBody = objectMapper.readTree(aiRequest.getBody().readUtf8());
        assertThat(aiRequestBody.path("genre").asText()).isEqualTo("都市情感");
        assertThat(aiRequestBody.path("audience").asText()).isEqualTo("女性");
        assertThat(aiRequestBody.path("keywords").asText()).isEqualTo("复仇");
        assertThat(aiRequestBody.path("storyId").asLong()).isEqualTo(storyId);

        mockMvc.perform(get("/api/story/{id}", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("GENERATED"))
                .andExpect(jsonPath("$.generatedTopics[0].title")
                        .value("离婚当天，我继承百亿集团"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_task WHERE story_id = ?",
                String.class,
                storyId
        )).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT result_payload FROM ai_task WHERE story_id = ?",
                String.class,
                storyId
        )).contains("\"generatedAt\":\"2026-07-30T12:00:00Z\"");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available_credits FROM user_ai_wallet WHERE user_id = (SELECT user_id FROM story_project WHERE id = ?)",
                Long.class,
                storyId
        )).isEqualTo(95L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_model_usage WHERE story_id = ? AND agent_type = 'TOPIC_GENERATION'",
                Integer.class,
                storyId
        )).isEqualTo(1);

        mockMvc.perform(put("/api/story/{id}/selection", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topicId":"1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SELECTED"))
                .andExpect(jsonPath("$.selectedTopic.id").value(1))
                .andExpect(jsonPath("$.selectedTopic.title")
                        .value("离婚当天，我继承百亿集团"));

        mockMvc.perform(get("/api/story/list")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(storyId))
                .andExpect(jsonPath("$[0].selectedTopic.id").value(1));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM product_event
                WHERE story_id=? AND event_name IN
                    ('STORY_CREATED','TOPICS_GENERATED','TOPIC_SELECTED')
        """, Long.class, storyId)).isEqualTo(3L);
    }

    @Test
    void novelProfileIsPersistedAndUsesNovelScoringContract() throws Exception {
        String token = register("novel-writer").path("token").asText();
        MvcResult created = mockMvc.perform(post("/api/story/create")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"长篇连载项目",
                                  "genre":"都市情感",
                                  "audience":"女性",
                                  "keywords":"家族、成长",
                                  "contentMode":"NOVEL",
                                  "targetChapterCount":30,
                                  "targetTotalWords":300000,
                                  "chapterTargetWords":2500,
                                  "viewpoint":"FIRST_PERSON",
                                  "styleProfile":{"tone":"克制"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentMode").value("NOVEL"))
                .andExpect(jsonPath("$.targetChapterCount").value(30))
                .andExpect(jsonPath("$.viewpoint").value("FIRST_PERSON"))
                .andReturn();
        long storyId = objectMapper.readTree(created.getResponse().getContentAsByteArray()).path("id").asLong();

        AI_SERVER.enqueue(jsonResponse(200, validAiResponse("novelFit")));
        mockMvc.perform(post("/api/ai/topic/generate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":%d,\"contentMode\":\"NOVEL\"}".formatted(storyId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics[0].scoreReasons.novelFit.score").value(90));

        RecordedRequest aiRequest = AI_SERVER.takeRequest(1, TimeUnit.SECONDS);
        assertThat(aiRequest).isNotNull();
        assertThat(objectMapper.readTree(aiRequest.getBody().readUtf8()).path("contentMode").asText())
                .isEqualTo("NOVEL");
    }

    @Test
    void shortStoryProfileRejectsFewerThanThreeChapters() throws Exception {
        String token = register("short-story-writer").path("token").asText();

        mockMvc.perform(post("/api/story/create")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"过短项目",
                                  "genre":"都市情感",
                                  "audience":"女性",
                                  "contentMode":"SHORT_STORY",
                                  "targetChapterCount":2
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SHORT_STORY_CHAPTER_COUNT_TOO_SMALL"));
    }

    @Test
    void insufficientCreditsAreRejectedBeforeCallingAiService() throws Exception {
        String token = register("no-topic-credits").path("token").asText();
        JsonNode story = createStory(token, "余额校验", "都市情感", "女性", "复仇");
        long storyId = story.path("id").asLong();
        jdbcTemplate.update(
                "UPDATE user_ai_wallet SET available_credits=0 WHERE user_id = (SELECT user_id FROM story_project WHERE id = ?)",
                storyId
        );
        int before = AI_SERVER.getRequestCount();

        mockMvc.perform(post("/api/ai/topic/generate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storyId\":%d,\"audience\":\"女性\",\"keywords\":\"复仇\"}".formatted(storyId)))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("AI_CREDITS_INSUFFICIENT"));

        assertThat(AI_SERVER.getRequestCount()).isEqualTo(before);
    }

    @Test
    void unavailableAiServiceReturns502AndPersistsFailure() throws Exception {
        String token = register("failure-writer").path("token").asText();
        long storyId = createStory(
                token,
                "服务失败也要留痕",
                "都市情感",
                "女性",
                "复仇"
        ).path("id").asLong();

        AI_SERVER.enqueue(jsonResponse(503, """
                {"detail":"LLM temporarily unavailable"}
                """));

        mockMvc.perform(post("/api/ai/topic/generate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storyId":%d}
                                """.formatted(storyId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("AI 选题生成失败：AI 服务返回 HTTP 503"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_task WHERE story_id = ?",
                String.class,
                storyId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM story_project WHERE id = ?",
                String.class,
                storyId
        )).isEqualTo("GENERATION_FAILED");
    }

    @Test
    void rejectsDirectionsOutsideAiContractBeforeCallingAiService() throws Exception {
        int requestCountBefore = AI_SERVER.getRequestCount();
        String token = register("contract-writer").path("token").asText();

        mockMvc.perform(post("/api/story/create")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title":"单字题材不应进入 AI",
                                  "genre":"爱",
                                  "audience":"女性",
                                  "keywords":"复仇"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        long storyId = createStory(
                token,
                "关键词边界",
                "都市情感",
                "女性",
                "复仇"
        ).path("id").asLong();

        mockMvc.perform(post("/api/ai/topic/generate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "storyId":%d,
                                  "keywords":"一,二,三,四,五,六,七,八,九,十,十一"
                                }
                                """.formatted(storyId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_KEYWORDS"));

        assertThat(AI_SERVER.getRequestCount()).isEqualTo(requestCountBefore);
    }

    @Test
    void invalidAiPayloadIsRejectedAndPersistedAsFailure() throws Exception {
        String token = register("invalid-ai-writer").path("token").asText();
        long storyId = createStory(
                token,
                "错误响应不能伪装成功",
                "都市情感",
                "女性",
                "复仇"
        ).path("id").asLong();

        AI_SERVER.enqueue(jsonResponse(200, """
                {
                  "topics": [],
                  "model": "broken-provider",
                  "generatedAt": "2026-07-30T12:00:00Z"
                }
                """));

        mockMvc.perform(post("/api/ai/topic/generate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"storyId":%d}
                                """.formatted(storyId)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("必须恰好包含 10 个选题")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_task WHERE story_id = ?",
                String.class,
                storyId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM story_project WHERE id = ?",
                String.class,
                storyId
        )).isEqualTo("GENERATION_FAILED");
    }

    private JsonNode register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"password123","privacyAccepted":true}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode createStory(
            String token,
            String title,
            String genre,
            String audience,
            String keywords
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/story/create")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(
                                objectMapper.createObjectNode()
                                        .put("title", title)
                                        .put("genre", genre)
                                        .put("audience", audience)
                                        .put("keywords", keywords)
                        )))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private static MockResponse jsonResponse(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody(body);
    }

    private String validAiResponse() throws Exception {
        return validAiResponse("shortDramaFit");
    }

    private String validAiResponse(String fitDimension) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", "local-template");
        root.put("generatedAt", "2026-07-30T12:00:00Z");

        var topics = root.putArray("topics");
        for (int index = 1; index <= 10; index++) {
            ObjectNode topic = topics.addObject();
            topic.put("id", index);
            topic.put(
                    "title",
                    index == 1 ? "离婚当天，我继承百亿集团" : "结构化故事方案 " + index
            );
            topic.put("hook", "开场冲突与身份反转 " + index);
            topic.put("summary", "主角从最低谷反击，并在连续冲突中揭开身份真相。" + index);
            topic.put("score", 92 - index + 1);
            topic.putArray("tags").add("都市情感").add("复仇");

            ObjectNode reasons = topic.putObject("scoreReasons");
            addCriterion(reasons, "conflict", 92, "开场即有明确冲突");
            addCriterion(reasons, "reversal", 91, "隐藏身份推动反转");
            addCriterion(reasons, "emotionalValue", 93, "逆袭提供情绪回报");
            addCriterion(reasons, fitDimension, 90,
                    "novelFit".equals(fitDimension) ? "具备长期连载空间" : "节奏适合短剧拆分");
        }
        return objectMapper.writeValueAsString(root);
    }

    private void addCriterion(
            ObjectNode reasons,
            String name,
            int score,
            String reason
    ) {
        ObjectNode detail = reasons.putObject(name);
        detail.put("score", score);
        detail.put("reason", reason);
    }

    private static MockWebServer startAiServer() {
        MockWebServer server = new MockWebServer();
        try {
            server.start();
            return server;
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
