package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.artifact.StoryArtifactService;
import com.storyforge.common.exception.ApiException;
import com.storyforge.story.StoryProject;
import com.storyforge.story.StoryResponse;
import com.storyforge.story.StoryService;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskService;
import com.storyforge.task.AiTaskStatus;
import com.storyforge.task.producer.WorkflowRequestPublisher;
import com.storyforge.workflow.dto.StartWorkflowRequest;
import com.storyforge.workflow.service.WorkflowService;
import com.storyforge.workflow.service.WorkflowTaskPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class WorkflowServiceConcurrencyTest {

    private static final long USER_ID = 10L;
    private static final long STORY_ID = 20L;
    private static final String START_KEY = STORY_ID + ":v1:START:0";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private StoryService storyService;
    private AiTaskService taskService;
    private WorkflowTaskPersistenceService persistenceService;
    private WorkflowRequestPublisher requestPublisher;
    private WorkflowService workflowService;
    private StoryProject story;

    @BeforeEach
    void setUp() {
        storyService = mock(StoryService.class);
        taskService = mock(AiTaskService.class);
        persistenceService = mock(WorkflowTaskPersistenceService.class);
        requestPublisher = mock(WorkflowRequestPublisher.class);
        workflowService = new WorkflowService(
                storyService,
                taskService,
                mock(StoryArtifactService.class),
                persistenceService,
                requestPublisher,
                objectMapper
        );
        story = new StoryProject();
        story.setId(STORY_ID);
        story.setUserId(USER_ID);
        when(storyService.requireOwned(USER_ID, STORY_ID)).thenReturn(story);
    }

    @Test
    void uniqueKeyFallbackRejectsTopicWonByConcurrentRequest() throws Exception {
        JsonNode requestedTopic = topic(2, "本次请求的选题");
        AiTask winningTask = startTask(topic(1, "并发胜出的选题"));
        arrangeUniqueKeyFallback(requestedTopic, winningTask);

        ApiException exception = catchThrowableOfType(
                () -> workflowService.start(
                        USER_ID,
                        STORY_ID,
                        new StartWorkflowRequest(objectMapper.readTree("2"))
                ),
                ApiException.class
        );

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(exception.getCode()).isEqualTo("WORKFLOW_TOPIC_LOCKED");
        verifyNoInteractions(requestPublisher);
    }

    @Test
    void uniqueKeyFallbackPublishesPersistedWinningTopicForEquivalentId() throws Exception {
        JsonNode requestedTopic = topic(1, "落败请求中的内容");
        JsonNode winningTopic = topic("1", "数据库中胜出的内容");
        AiTask winningTask = startTask(winningTopic);
        arrangeUniqueKeyFallback(requestedTopic, winningTask);
        when(requestPublisher.publish(any())).thenReturn("request-1");

        workflowService.start(
                USER_ID,
                STORY_ID,
                new StartWorkflowRequest(objectMapper.readTree("1"))
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(requestPublisher).publish(fields.capture());
        assertThat(objectMapper.readTree(fields.getValue().get("topic")).path("title").asText())
                .isEqualTo("数据库中胜出的内容");
    }

    private void arrangeUniqueKeyFallback(JsonNode requestedTopic, AiTask winningTask) {
        when(taskService.findByIdempotencyKey(USER_ID, START_KEY))
                .thenReturn(null, winningTask);
        when(storyService.selectTopic(eq(USER_ID), eq(STORY_ID), any()))
                .thenReturn(new StoryResponse(
                        STORY_ID,
                        USER_ID,
                        "故事",
                        "都市情感",
                        "女性",
                        "复仇",
                        "SELECTED",
                        requestedTopic,
                        objectMapper.createArrayNode(),
                        null,
                        null
                ));
        when(persistenceService.createStartTask(USER_ID, story, requestedTopic))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));
    }

    private AiTask startTask(JsonNode topic) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("action", "START");
        request.set("topic", topic);
        AiTask task = new AiTask();
        task.setId(99L);
        task.setUserId(USER_ID);
        task.setStoryId(STORY_ID);
        task.setTaskType(WorkflowTaskPersistenceService.TASK_TYPE_START);
        task.setStatus(AiTaskStatus.WAITING);
        task.setIdempotencyKey(START_KEY);
        task.setRequestPayload(objectMapper.writeValueAsString(request));
        return task;
    }

    private ObjectNode topic(Object id, String title) {
        ObjectNode topic = objectMapper.createObjectNode();
        if (id instanceof Number number) {
            topic.put("id", number.longValue());
        } else {
            topic.put("id", String.valueOf(id));
        }
        topic.put("title", title);
        topic.put("hook", "身份反转");
        return topic;
    }
}
