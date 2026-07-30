package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.common.exception.ApiException;
import com.storyforge.story.StoryProject;
import com.storyforge.task.consumer.WorkflowEventService;
import com.storyforge.task.producer.WorkflowDispatchException;
import com.storyforge.task.producer.WorkflowRequestPublisher;
import com.storyforge.workflow.dto.StartWorkflowRequest;
import com.storyforge.workflow.service.WorkflowService;
import com.storyforge.workflow.service.WorkflowTaskPersistenceService;
import com.storyforge.workflow.vo.WorkflowTaskCreatedResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("local")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:storyforge-workflow;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.workflow.redis-enabled=false"
})
class WorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkflowEventService eventService;

    @Autowired
    private WorkflowService workflowService;

    @MockBean
    private WorkflowRequestPublisher requestPublisher;

    @SpyBean
    private WorkflowTaskPersistenceService taskPersistenceService;

    @BeforeEach
    void cleanDatabase() {
        reset(requestPublisher);
        when(requestPublisher.publish(anyMap())).thenReturn("request-1");
        jdbcTemplate.update("DELETE FROM story_artifact");
        jdbcTemplate.update("DELETE FROM ai_task");
        jdbcTemplate.update("DELETE FROM story_project");
        jdbcTemplate.update("DELETE FROM sys_user");
    }

    @Test
    void workflowCanStartPersistEventsExposeHistoryAndResumeWithSameThread() throws Exception {
        String token = register("workflow-writer").path("token").asText();
        long storyId = createStory(token, "复仇之后", "都市情感").path("id").asLong();
        seedTopics(storyId);

        MvcResult startResult = mockMvc.perform(post("/api/stories/{storyId}/workflow", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":\"1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.taskId").isNumber())
                .andReturn();
        long taskId = body(startResult).path("taskId").asLong();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(requestPublisher).publish(requestCaptor.capture());
        Map<String, String> startMessage = requestCaptor.getValue();
        assertThat(startMessage).containsEntry("taskId", String.valueOf(taskId));
        assertThat(startMessage).containsEntry("storyId", String.valueOf(storyId));
        assertThat(startMessage).containsEntry("action", "START");
        assertThat(startMessage).containsEntry("payloadVersion", "1");
        assertThat(startMessage).containsEntry("idempotencyKey", storyId + ":v1:START:0");
        assertThat(objectMapper.readTree(startMessage.get("topic")).path("title").asText())
                .isEqualTo("离婚当天，我继承百亿集团");

        Map<String, String> runningEvent = event(taskId, storyId, "thread-abc", "RUNNING");
        runningEvent.put("currentNode", "generate_outline");
        runningEvent.put("progress", "40");
        runningEvent.put("attemptNo", "0");
        runningEvent.put("progressEvents", """
                [{"node":"character","status":"completed","message":"人物设定已生成"}]
                """);
        assertThat(eventService.processEvent("1000-0", runningEvent)).isTrue();

        Map<String, String> reviewEvent = event(taskId, storyId, "thread-abc", "REVIEW_REQUIRED");
        reviewEvent.put("currentNode", "human_review");
        reviewEvent.put("progress", "85");
        reviewEvent.put("attemptNo", "2");
        reviewEvent.put("revisionCount", "2");
        reviewEvent.put("maxRevisions", "2");
        reviewEvent.put("artifacts", workflowArtifacts().toString());
        reviewEvent.put("progressEvents", """
                [
                  {"node":"character","status":"completed","message":"人物设定已生成"},
                  {"node":"outline","status":"completed","message":"20节点大纲已生成"},
                  {"node":"score","status":"completed","message":"第二次评分：84分"},
                  {"node":"human_review","status":"waiting","message":"等待用户审核"}
                ]
                """);
        reviewEvent.put("inputTokens", "3100");
        reviewEvent.put("outputTokens", "5200");
        reviewEvent.put("durationMs", "43000");
        assertThat(eventService.processEvent("1001-0", reviewEvent)).isTrue();

        // Redelivery and a stale pending event must not overwrite data or create versions.
        assertThat(eventService.processEvent("1001-0", reviewEvent)).isFalse();
        Map<String, String> staleEvent = event(taskId, storyId, "thread-abc", "RUNNING");
        staleEvent.put("progress", "50");
        assertThat(eventService.processEvent("1000-9", staleEvent)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM story_artifact WHERE story_id = ?",
                Integer.class,
                storyId
        )).isEqualTo(5);

        mockMvc.perform(get("/api/ai-tasks/{taskId}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.storyId").value(storyId))
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.currentNode").value("human_review"))
                .andExpect(jsonPath("$.progress").value(85))
                .andExpect(jsonPath("$.threadId").value("thread-abc"))
                .andExpect(jsonPath("$.score").value(84))
                .andExpect(jsonPath("$.revisionCount").value(2))
                .andExpect(jsonPath("$.maxRevisions").value(2))
                .andExpect(jsonPath("$.progressEvents[3].status").value("waiting"));

        mockMvc.perform(get("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.storyId").value(storyId))
                .andExpect(jsonPath("$.threadId").value("thread-abc"))
                .andExpect(jsonPath("$.revisionCount").value(2))
                .andExpect(jsonPath("$.characters.length()").value(3))
                .andExpect(jsonPath("$.outline.title").value("修改稿"))
                .andExpect(jsonPath("$.outline.coreConflict").value("复仇与宽恕"))
                .andExpect(jsonPath("$.outline.nodes.length()").value(20))
                .andExpect(jsonPath("$.score.total").value(84))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].versionNo").value(1))
                .andExpect(jsonPath("$.versions[0].outline.title").value("初稿"))
                .andExpect(jsonPath("$.versions[0].score.total").value(74))
                .andExpect(jsonPath("$.versions[1].versionNo").value(2))
                .andExpect(jsonPath("$.versions[1].outline.title").value("修改稿"));

        String otherToken = register("workflow-other").path("token").asText();
        mockMvc.perform(get("/api/ai-tasks/{taskId}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AI_TASK_FORBIDDEN"));

        MvcResult resumeResult = mockMvc.perform(post("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "approved": false,
                                  "notes": "请提前铺垫妹妹与反派的利益关系。"
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andReturn();
        long resumeTaskId = body(resumeResult).path("taskId").asLong();
        assertThat(resumeTaskId).isNotEqualTo(taskId);

        verify(requestPublisher, org.mockito.Mockito.times(2)).publish(requestCaptor.capture());
        List<Map<String, String>> published = requestCaptor.getAllValues();
        Map<String, String> resumeMessage = published.get(published.size() - 1);
        assertThat(resumeMessage).containsEntry("taskId", String.valueOf(resumeTaskId));
        assertThat(resumeMessage).containsEntry("threadId", "thread-abc");
        assertThat(resumeMessage).containsEntry("action", "RESUME");
        assertThat(resumeMessage).containsEntry("approved", "false");
        assertThat(resumeMessage).containsEntry("notes", "请提前铺垫妹妹与反派的利益关系。");
        assertThat(resumeMessage).containsEntry("idempotencyKey", storyId + ":v1:RESUME:1");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT parent_task_id FROM ai_task WHERE id = ?",
                Long.class,
                resumeTaskId
        )).isEqualTo(taskId);
    }

    @Test
    void latestWorkflowTaskReturnsNewestResumeAcrossLoginAndChecksOwnership() throws Exception {
        String username = "latest-workflow";
        String ownerToken = register(username).path("token").asText();
        String otherToken = register("latest-other").path("token").asText();
        long storyId = createStory(ownerToken, "重新打开工作流", "都市情感").path("id").asLong();

        mockMvc.perform(get("/api/stories/{storyId}/workflow/latest", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WORKFLOW_TASK_NOT_FOUND"));
        mockMvc.perform(get("/api/stories/{storyId}/workflow/latest", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORY_FORBIDDEN"));

        seedTopics(storyId);
        long startTaskId = startWorkflow(ownerToken, storyId);
        mockMvc.perform(get("/api/stories/{storyId}/workflow/latest", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(startTaskId))
                .andExpect(jsonPath("$.status").value("WAITING"));

        Map<String, String> reviewEvent = event(
                startTaskId,
                storyId,
                "thread-latest",
                "REVIEW_REQUIRED"
        );
        reviewEvent.put("progress", "85");
        assertThat(eventService.processEvent("2500-0", reviewEvent)).isTrue();
        long resumeTaskId = body(mockMvc.perform(post("/api/ai-tasks/{taskId}/review", startTaskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"notes\":\"加强身份反转\"}"))
                .andExpect(status().isAccepted())
                .andReturn()).path("taskId").asLong();

        // A newer non-workflow AI task must not hide the resumable workflow task.
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM story_project WHERE id = ?",
                Long.class,
                storyId
        );
        jdbcTemplate.update(
                """
                INSERT INTO ai_task
                    (user_id, story_id, task_type, status, request_payload, created_time, updated_time)
                VALUES (?, ?, 'TOPIC_GENERATION', 'SUCCESS', '{}', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                userId,
                storyId
        );

        String reloginToken = login(username).path("token").asText();
        mockMvc.perform(get("/api/stories/{storyId}/workflow/latest", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(reloginToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(resumeTaskId))
                .andExpect(jsonPath("$.storyId").value(storyId))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.threadId").value("thread-latest"));
    }

    @Test
    void workflowLocksOriginalTopicAndExposesItForStartAndResumeTasks() throws Exception {
        String token = register("locked-topic").path("token").asText();
        long storyId = createStory(token, "选题锁定", "都市情感").path("id").asLong();
        seedTopics(storyId);
        long startTaskId = startWorkflow(token, storyId);

        // Numeric and string representations of the same scalar ID are equivalent.
        mockMvc.perform(post("/api/stories/{storyId}/workflow", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":\"1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(startTaskId));

        mockMvc.perform(post("/api/stories/{storyId}/workflow", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_TOPIC_LOCKED"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_task WHERE story_id = ? AND task_type = 'STORY_WORKFLOW'",
                Integer.class,
                storyId
        )).isEqualTo(1);

        // The status contract uses immutable START payload data, not mutable story selection.
        jdbcTemplate.update(
                "UPDATE story_project SET selected_topic = ? WHERE id = ?",
                "{\"id\":2,\"title\":\"另一个选题\"}",
                storyId
        );
        mockMvc.perform(get("/api/ai-tasks/{taskId}", startTaskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topicId").value(1));
        mockMvc.perform(get("/api/stories/{storyId}/workflow/latest", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(startTaskId))
                .andExpect(jsonPath("$.topicId").value(1));

        Map<String, String> reviewEvent = event(
                startTaskId,
                storyId,
                "thread-topic-lock",
                "REVIEW_REQUIRED"
        );
        assertThat(eventService.processEvent("2550-0", reviewEvent)).isTrue();
        long resumeTaskId = body(mockMvc.perform(post("/api/ai-tasks/{taskId}/review", startTaskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"notes\":\"增强冲突\"}"))
                .andExpect(status().isAccepted())
                .andReturn()).path("taskId").asLong();
        mockMvc.perform(get("/api/stories/{storyId}/workflow/latest", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(resumeTaskId))
                .andExpect(jsonPath("$.topicId").value(1));
    }

    @Test
    void workflowReviewEndpointsRejectChapterTasks() throws Exception {
        JsonNode registration = register("chapter-task-review");
        String token = registration.path("token").asText();
        long userId = registration.path("userId").asLong();
        long storyId = createStory(token, "章节任务", "都市情感").path("id").asLong();
        jdbcTemplate.update("""
                INSERT INTO ai_task(
                    user_id,story_id,task_type,status,request_payload,thread_id,
                    idempotency_key,created_time,updated_time
                )
                VALUES (?,?,'CHAPTER_GENERATE','REVIEW_REQUIRED','{}','chapter-thread',
                        'chapter-review-task',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, userId, storyId);
        long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_task WHERE idempotency_key='chapter-review-task'",
                Long.class
        );

        mockMvc.perform(get("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_TASK_TYPE_MISMATCH"));
        mockMvc.perform(post("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"notes\":\"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_TASK_TYPE_MISMATCH"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_task WHERE parent_task_id=?",
                Integer.class,
                taskId
        )).isZero();
    }

    @Test
    void concurrentDifferentStartsKeepStoryAlignedWithWinningTask() throws Exception {
        JsonNode registration = register("concurrent-start");
        long userId = registration.path("userId").asLong();
        String token = registration.path("token").asText();
        long storyId = createStory(token, "并发选题", "都市情感").path("id").asLong();
        seedTopics(storyId);

        CyclicBarrier bothSelectionsCompleted = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothSelectionsCompleted.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(taskPersistenceService).createStartTask(
                org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.any(StoryProject.class),
                org.mockito.ArgumentMatchers.any(JsonNode.class)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Object first;
        Object second;
        try {
            CompletableFuture<Object> firstStart = CompletableFuture.supplyAsync(
                    () -> startOutcome(userId, storyId, 1),
                    executor
            );
            CompletableFuture<Object> secondStart = CompletableFuture.supplyAsync(
                    () -> startOutcome(userId, storyId, 2),
                    executor
            );
            first = firstStart.get(30, TimeUnit.SECONDS);
            second = secondStart.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        List<Object> outcomes = List.of(first, second);
        assertThat(outcomes.stream().filter(WorkflowTaskCreatedResponse.class::isInstance).count())
                .isEqualTo(1);
        ApiException losingRequest = outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(losingRequest.getCode()).isEqualTo("WORKFLOW_TOPIC_LOCKED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_task WHERE story_id = ? AND task_type = 'STORY_WORKFLOW'",
                Integer.class,
                storyId
        )).isEqualTo(1);
        String requestPayload = jdbcTemplate.queryForObject(
                "SELECT request_payload FROM ai_task WHERE story_id = ? AND task_type = 'STORY_WORKFLOW'",
                String.class,
                storyId
        );
        JsonNode winningTopic = objectMapper.readTree(requestPayload).path("topic");
        JsonNode selectedTopic = objectMapper.readTree(jdbcTemplate.queryForObject(
                "SELECT selected_topic FROM story_project WHERE id = ?",
                String.class,
                storyId
        ));
        assertThat(selectedTopic).isEqualTo(winningTopic);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM story_project WHERE id = ?",
                String.class,
                storyId
        )).isEqualTo("SELECTED");
    }

    @Test
    void directSelectionCannotChangeWaitingReviewOrSuccessfulWorkflow() throws Exception {
        String token = register("selection-lock").path("token").asText();
        long storyId = createStory(token, "禁止重选", "都市情感").path("id").asLong();
        seedTopics(storyId);
        long taskId = startWorkflow(token, storyId);
        assertSelectionLocked(token, storyId, "SELECTED");

        Map<String, String> reviewEvent = event(
                taskId,
                storyId,
                "thread-selection-lock",
                "REVIEW_REQUIRED"
        );
        reviewEvent.put("currentNode", "human_review");
        assertThat(eventService.processEvent("2560-0", reviewEvent)).isTrue();
        assertSelectionLocked(token, storyId, "WORKFLOW_REVIEW_REQUIRED");

        Map<String, String> successEvent = event(
                taskId,
                storyId,
                "thread-selection-lock",
                "SUCCESS"
        );
        successEvent.put("currentNode", "finish");
        successEvent.put("progress", "100");
        assertThat(eventService.processEvent("2561-0", successEvent)).isTrue();
        assertSelectionLocked(token, storyId, "WORKFLOW_COMPLETED");
    }

    @Test
    void replayFromOlderWorkflowTaskCannotRegressLatestStoryStatus() throws Exception {
        String token = register("old-task-replay").path("token").asText();
        long storyId = createStory(token, "旧任务重放", "都市情感").path("id").asLong();
        seedTopics(storyId);
        long startTaskId = startWorkflow(token, storyId);

        Map<String, String> initialReview = event(
                startTaskId,
                storyId,
                "thread-old-replay",
                "REVIEW_REQUIRED"
        );
        initialReview.put("currentNode", "human_review");
        assertThat(eventService.processEvent("2580-0", initialReview)).isTrue();
        long resumeTaskId = body(mockMvc.perform(post("/api/ai-tasks/{taskId}/review", startTaskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"notes\":\"\"}"))
                .andExpect(status().isAccepted())
                .andReturn()).path("taskId").asLong();

        Map<String, String> resumeSuccess = event(
                resumeTaskId,
                storyId,
                "thread-old-replay",
                "SUCCESS"
        );
        resumeSuccess.put("idempotencyKey", storyId + ":v1:RESUME:1");
        resumeSuccess.put("currentNode", "finish");
        resumeSuccess.put("progress", "100");
        assertThat(eventService.processEvent("2581-0", resumeSuccess)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM story_project WHERE id = ?",
                String.class,
                storyId
        )).isEqualTo("WORKFLOW_COMPLETED");

        // Simulate a lost backend XACK: the old START terminal event is replayed
        // with a new stream ID after the newer RESUME operation completed.
        Map<String, String> replayedReview = event(
                startTaskId,
                storyId,
                "thread-old-replay",
                "REVIEW_REQUIRED"
        );
        replayedReview.put("currentNode", "human_review");
        assertThat(eventService.processEvent("2582-0", replayedReview)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM story_project WHERE id = ?",
                String.class,
                storyId
        )).isEqualTo("WORKFLOW_COMPLETED");
    }

    @Test
    void reviewUsesHighestMatchedArtifactVersionAndCannotRegress() throws Exception {
        String token = register("matched-review").path("token").asText();
        long storyId = createStory(token, "版本一致性", "都市情感").path("id").asLong();
        seedTopics(storyId);
        long taskId = startWorkflow(token, storyId);

        ArrayNode partialArtifacts = workflowArtifacts();
        partialArtifacts.remove(partialArtifacts.size() - 1); // OUTLINE v2 has no SCORE v2 yet.
        Map<String, String> reviewEvent = event(taskId, storyId, "thread-matched", "REVIEW_REQUIRED");
        reviewEvent.put("currentNode", "human_review");
        reviewEvent.put("progress", "85");
        reviewEvent.put("artifacts", partialArtifacts.toString());
        assertThat(eventService.processEvent("2600-0", reviewEvent)).isTrue();

        mockMvc.perform(get("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.outline.title").value("初稿"))
                .andExpect(jsonPath("$.score.total").value(74))
                .andExpect(jsonPath("$.revisionCount").value(0))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].versionNo").value(1));

        Map<String, String> runningEvent = event(taskId, storyId, "thread-matched", "RUNNING");
        runningEvent.put("currentNode", "revise_outline");
        runningEvent.put("progress", "50");
        assertThat(eventService.processEvent("2601-0", runningEvent)).isFalse();
        Map<String, String> failedEvent = event(taskId, storyId, "thread-matched", "FAILED");
        failedEvent.put("errorCode", "LATE_FAILURE");
        failedEvent.put("errorMessage", "暂停完成后的迟到失败事件");
        ArrayNode lateArtifacts = objectMapper.createArrayNode();
        addArtifact(lateArtifacts, "SCORE", 2, "FAILED", score(12, "C"));
        failedEvent.put("artifacts", lateArtifacts.toString());
        assertThat(eventService.processEvent("2602-0", failedEvent)).isFalse();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_task WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo("REVIEW_REQUIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT current_node FROM ai_task WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo("human_review");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM story_project WHERE id = ?",
                String.class,
                storyId
        )).isEqualTo("WORKFLOW_REVIEW_REQUIRED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM story_artifact WHERE story_id = ? AND artifact_type = 'SCORE'",
                Integer.class,
                storyId
        )).isEqualTo(1);
    }

    @Test
    void successUsesFinalArtifactStaysTerminalAndFailedTaskCanRecover() throws Exception {
        String token = register("terminal-workflow").path("token").asText();
        long storyId = createStory(token, "终态保护", "都市情感").path("id").asLong();
        seedTopics(storyId);
        long taskId = startWorkflow(token, storyId);

        Map<String, String> failedEvent = event(taskId, storyId, "thread-terminal", "FAILED");
        failedEvent.put("errorCode", "MODEL_TIMEOUT");
        assertThat(eventService.processEvent("2700-0", failedEvent)).isTrue();
        Map<String, String> recoveredEvent = event(taskId, storyId, "thread-terminal", "RUNNING");
        recoveredEvent.put("currentNode", "retrying");
        recoveredEvent.put("progress", "30");
        recoveredEvent.put("errorCode", "");
        recoveredEvent.put("errorMessage", "");
        assertThat(eventService.processEvent("2701-0", recoveredEvent)).isTrue();

        Map<String, String> successEvent = event(taskId, storyId, "thread-terminal", "SUCCESS");
        successEvent.put("currentNode", "finish");
        successEvent.put("progress", "100");
        successEvent.put("artifacts", finalWorkflowArtifacts().toString());
        assertThat(eventService.processEvent("2702-0", successEvent)).isTrue();

        mockMvc.perform(get("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.characters[0].name").value("终稿主角"))
                .andExpect(jsonPath("$.outline.title").value("已确认终稿"))
                .andExpect(jsonPath("$.outline.nodes.length()").value(20))
                .andExpect(jsonPath("$.score.total").value(99))
                .andExpect(jsonPath("$.revisionCount").value(1))
                .andExpect(jsonPath("$.versions.length()").value(1))
                .andExpect(jsonPath("$.versions[0].versionNo").value(1));

        Map<String, String> lateFailed = event(taskId, storyId, "thread-terminal", "FAILED");
        lateFailed.put("currentNode", "failed_after_finish");
        lateFailed.put("progress", "10");
        lateFailed.put("errorCode", "LATE_FAILURE");
        assertThat(eventService.processEvent("2703-0", lateFailed)).isFalse();
        Map<String, String> lateRunning = event(taskId, storyId, "thread-terminal", "RUNNING");
        assertThat(eventService.processEvent("2704-0", lateRunning)).isFalse();

        mockMvc.perform(get("/api/ai-tasks/{taskId}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.currentNode").value("finish"))
                .andExpect(jsonPath("$.progress").value(100))
                .andExpect(jsonPath("$.score").value(99))
                .andExpect(jsonPath("$.revisionCount").value(1))
                .andExpect(jsonPath("$.errorCode").doesNotExist());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM story_project WHERE id = ?",
                String.class,
                storyId
        )).isEqualTo("WORKFLOW_COMPLETED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT last_event_id FROM ai_task WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo("2702-0");
    }

    @Test
    void queueFailureIsVisibleAndRetryUsesSameIdempotentTask() throws Exception {
        String token = register("queue-retry").path("token").asText();
        long storyId = createStory(token, "队列重试", "都市情感").path("id").asLong();
        seedTopics(storyId);
        doThrow(new WorkflowDispatchException("Redis connection refused"))
                .when(requestPublisher).publish(anyMap());

        mockMvc.perform(post("/api/stories/{storyId}/workflow", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":1}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WORKFLOW_QUEUE_UNAVAILABLE"));

        Long failedTaskId = jdbcTemplate.queryForObject(
                "SELECT id FROM ai_task WHERE story_id = ?",
                Long.class,
                storyId
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_task WHERE id = ?",
                String.class,
                failedTaskId
        )).isEqualTo("FAILED");

        doReturn("request-retry").when(requestPublisher).publish(anyMap());
        mockMvc.perform(post("/api/stories/{storyId}/workflow", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":\"1\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(failedTaskId))
                .andExpect(jsonPath("$.status").value("WAITING"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_task WHERE story_id = ?",
                Integer.class,
                storyId
        )).isEqualTo(1);
    }

    @Test
    void reviewRequiresOwnershipStateThreadAndNotes() throws Exception {
        String ownerToken = register("review-owner").path("token").asText();
        String otherToken = register("review-other").path("token").asText();
        long storyId = createStory(ownerToken, "审核约束", "都市情感").path("id").asLong();
        seedTopics(storyId);
        long taskId = body(mockMvc.perform(post("/api/stories/{storyId}/workflow", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":1}"))
                .andReturn()).path("taskId").asLong();

        mockMvc.perform(post("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"notes\":\"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_NOT_REQUIRED"));

        Map<String, String> reviewEvent = event(taskId, storyId, "thread-review", "REVIEW_REQUIRED");
        reviewEvent.put("progress", "85");
        assertThat(eventService.processEvent("2000-0", reviewEvent)).isTrue();

        mockMvc.perform(post("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":false,\"notes\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REVIEW_NOTES_REQUIRED"));

        mockMvc.perform(post("/api/ai-tasks/{taskId}/review", taskId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approved\":true,\"notes\":\"\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AI_TASK_FORBIDDEN"));
    }

    private ArrayNode workflowArtifacts() {
        ArrayNode artifacts = objectMapper.createArrayNode();
        ObjectNode characters = objectMapper.createObjectNode();
        ArrayNode characterList = characters.putArray("characters");
        characterList.addObject().put("name", "林晚").put("role", "主角");
        characterList.addObject().put("name", "陈宇").put("role", "反派");
        characterList.addObject().put("name", "林雪").put("role", "关键配角");
        addArtifact(artifacts, "CHARACTER", 1, "GENERATED", characters);
        addArtifact(artifacts, "OUTLINE", 1, "GENERATED", outline("初稿"));
        addArtifact(artifacts, "SCORE", 1, "GENERATED", score(74, "B"));
        addArtifact(artifacts, "OUTLINE", 2, "REVIEW", outline("修改稿"));
        addArtifact(artifacts, "SCORE", 2, "REVIEW", score(84, "A"));
        return artifacts;
    }

    private ArrayNode finalWorkflowArtifacts() {
        ArrayNode artifacts = objectMapper.createArrayNode();
        ObjectNode draftCharacters = objectMapper.createObjectNode();
        draftCharacters.putArray("characters")
                .addObject()
                .put("name", "草稿主角")
                .put("role", "主角");
        addArtifact(artifacts, "CHARACTER", 1, "DRAFT", draftCharacters);
        addArtifact(artifacts, "OUTLINE", 1, "DRAFT", outline("待确认稿"));
        addArtifact(artifacts, "SCORE", 1, "DRAFT", score(81, "A"));

        ObjectNode finalContent = objectMapper.createObjectNode();
        finalContent.putArray("characters")
                .addObject()
                .put("name", "终稿主角")
                .put("role", "主角");
        finalContent.putObject("outlineMetadata")
                .put("title", "已确认终稿")
                .put("coreConflict", "真相与选择")
                .put("endingType", "圆满");
        finalContent.set("outline", outline("已确认终稿").path("nodes"));
        finalContent.set("score", score(99, "S"));
        addArtifact(artifacts, "WORKFLOW_FINAL", 2, "APPROVED", finalContent);
        return artifacts;
    }

    private ObjectNode outline(String title) {
        ObjectNode outline = objectMapper.createObjectNode();
        outline.put("title", title);
        outline.put("coreConflict", "复仇与宽恕");
        outline.put("endingType", "情绪释放");
        ArrayNode nodes = outline.putArray("nodes");
        for (int index = 1; index <= 20; index++) {
            nodes.addObject()
                    .put("nodeNo", index)
                    .put("event", "剧情节点 " + index)
                    .put("isTwist", index % 5 == 0);
        }
        return outline;
    }

    private ObjectNode score(int total, String level) {
        ObjectNode score = objectMapper.createObjectNode();
        score.put("total", total);
        score.put("level", level);
        return score;
    }

    private void addArtifact(
            ArrayNode artifacts,
            String type,
            int version,
            String status,
            JsonNode content
    ) {
        ObjectNode artifact = artifacts.addObject();
        artifact.put("artifactType", type);
        artifact.put("versionNo", version);
        artifact.put("status", status);
        artifact.set("content", content);
        artifact.put("promptVersion", "v1");
        artifact.put("modelName", "test-model");
    }

    private Map<String, String> event(
            long taskId,
            long storyId,
            String threadId,
            String status
    ) {
        Map<String, String> event = new LinkedHashMap<>();
        event.put("taskId", String.valueOf(taskId));
        event.put("storyId", String.valueOf(storyId));
        event.put("threadId", threadId);
        event.put("status", status);
        event.put("idempotencyKey", storyId + ":v1:START:0");
        return event;
    }

    private void seedTopics(long storyId) {
        jdbcTemplate.update(
                "UPDATE story_project SET generated_topics = ?, status = 'GENERATED' WHERE id = ?",
                """
                        [
                          {"id":1,"title":"离婚当天，我继承百亿集团","hook":"身份反转","score":92},
                          {"id":2,"title":"被赶出家门后，我成了财阀继承人","hook":"逆袭反转","score":89}
                        ]
                        """,
                storyId
        );
    }

    private JsonNode register(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"password123"}
                                """.formatted(username)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    private JsonNode login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"password123"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn();
        return body(result);
    }

    private long startWorkflow(String token, long storyId) throws Exception {
        return body(mockMvc.perform(post("/api/stories/{storyId}/workflow", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andReturn()).path("taskId").asLong();
    }

    private Object startOutcome(long userId, long storyId, int topicId) {
        try {
            return workflowService.start(
                    userId,
                    storyId,
                    new StartWorkflowRequest(objectMapper.getNodeFactory().numberNode(topicId))
            );
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private void assertSelectionLocked(String token, long storyId, String expectedStatus)
            throws Exception {
        mockMvc.perform(put("/api/story/{id}/selection", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topicId\":2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_TOPIC_LOCKED"));
        mockMvc.perform(get("/api/story/{id}", storyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedTopic.id").value(1))
                .andExpect(jsonPath("$.status").value(expectedStatus));
    }

    private JsonNode createStory(String token, String title, String genre) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/story/create")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","genre":"%s","audience":"女性","keywords":"复仇"}
                                """.formatted(title, genre)))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
