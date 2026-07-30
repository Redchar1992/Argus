package com.storyforge.ai;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.common.exception.ApiException;
import com.storyforge.common.validation.CreativeDirectionValidator;
import com.storyforge.story.StoryProject;
import com.storyforge.story.StoryProjectMapper;
import com.storyforge.story.StoryService;
import com.storyforge.story.StoryStatus;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import com.storyforge.task.AiTaskStatus;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AiOrchestrationService {

    private static final String TASK_TYPE_TOPIC = "TOPIC_GENERATION";
    private static final int EXPECTED_TOPIC_COUNT = 10;
    private static final Set<String> SCORE_DIMENSIONS = Set.of(
            "conflict",
            "reversal",
            "emotionalValue",
            "shortDramaFit"
    );

    private final StoryService storyService;
    private final StoryProjectMapper storyMapper;
    private final AiTaskMapper taskMapper;
    private final AiServiceClient aiServiceClient;
    private final ObjectMapper objectMapper;

    public AiOrchestrationService(
            StoryService storyService,
            StoryProjectMapper storyMapper,
            AiTaskMapper taskMapper,
            AiServiceClient aiServiceClient,
            ObjectMapper objectMapper
    ) {
        this.storyService = storyService;
        this.storyMapper = storyMapper;
        this.taskMapper = taskMapper;
        this.aiServiceClient = aiServiceClient;
        this.objectMapper = objectMapper;
    }

    public JsonNode generate(Long userId, GenerateTopicRequest request) {
        StoryProject story = storyService.requireOwned(userId, request.storyId());
        AiTopicRequest aiRequest = resolveDirection(story, request);
        LocalDateTime now = LocalDateTime.now();

        story.setGenre(aiRequest.genre());
        story.setAudience(aiRequest.audience());
        story.setKeywords(aiRequest.keywords());
        story.setStatus(StoryStatus.GENERATING);
        story.setUpdatedTime(now);
        storyMapper.updateById(story);

        AiTask task = new AiTask();
        task.setUserId(userId);
        task.setStoryId(story.getId());
        task.setTaskType(TASK_TYPE_TOPIC);
        task.setStatus(AiTaskStatus.RUNNING);
        task.setRequestPayload(writeJson(objectMapper.valueToTree(aiRequest)));
        task.setCreatedTime(now);
        task.setUpdatedTime(now);
        taskMapper.insert(task);

        try {
            JsonNode upstreamResult = aiServiceClient.generateTopics(aiRequest);
            JsonNode topics = extractTopics(upstreamResult);
            LocalDateTime completedAt = LocalDateTime.now();

            task.setStatus(AiTaskStatus.SUCCESS);
            task.setResultPayload(writeJson(upstreamResult));
            task.setUpdatedTime(completedAt);
            taskMapper.updateById(task);

            story.setGeneratedTopics(writeJson(topics));
            story.setSelectedTopic(null);
            story.setStatus(StoryStatus.GENERATED);
            story.setUpdatedTime(completedAt);
            storyMapper.updateById(story);

            return buildResponse(upstreamResult, topics, task.getId(), story.getId());
        } catch (AiServiceException exception) {
            markFailed(task, story, exception.getMessage());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AI_SERVICE_UNAVAILABLE",
                    "AI 选题生成失败：" + exception.getMessage()
            );
        }
    }

    private AiTopicRequest resolveDirection(StoryProject story, GenerateTopicRequest request) {
        String genre = CreativeDirectionValidator.genre(
                firstText(request.genre(), story.getGenre())
        );
        String audience = CreativeDirectionValidator.audience(
                firstText(request.audience(), story.getAudience()),
                true
        );
        String keywords = CreativeDirectionValidator.keywords(
                firstText(request.keywords(), story.getKeywords())
        );
        return new AiTopicRequest(
                story.getId(),
                genre,
                audience,
                keywords == null ? "" : keywords
        );
    }

    private JsonNode extractTopics(JsonNode upstreamResult) {
        if (!upstreamResult.isObject()) {
            throw invalidAiResponse("顶层必须是 JSON 对象");
        }
        requireText(upstreamResult, "model", "顶层缺少 model");
        String generatedAt = requireText(
                upstreamResult,
                "generatedAt",
                "顶层缺少 generatedAt"
        );
        try {
            OffsetDateTime.parse(generatedAt);
        } catch (DateTimeParseException exception) {
            throw invalidAiResponse("generatedAt 不是有效的 ISO-8601 时间");
        }

        JsonNode topics = upstreamResult.get("topics");
        if (topics == null || !topics.isArray()) {
            throw invalidAiResponse("缺少 topics 数组");
        }
        if (topics.size() != EXPECTED_TOPIC_COUNT) {
            throw invalidAiResponse("topics 必须恰好包含 10 个选题");
        }

        Set<String> ids = new HashSet<>();
        for (int index = 0; index < topics.size(); index++) {
            validateTopic(topics.get(index), index, ids);
        }
        return topics;
    }

    private void validateTopic(JsonNode topic, int index, Set<String> ids) {
        String path = "topics[" + index + "]";
        if (topic == null || !topic.isObject()) {
            throw invalidAiResponse(path + " 必须是 JSON 对象");
        }

        JsonNode id = topic.get("id");
        if (id == null || id.isNull() || id.isContainerNode() || !StringUtils.hasText(id.asText())) {
            throw invalidAiResponse(path + ".id 必须是数字或字符串");
        }
        if (!ids.add(id.asText())) {
            throw invalidAiResponse(path + ".id 不能重复");
        }

        requireText(topic, "title", path + ".title 不能为空");
        requireText(topic, "hook", path + ".hook 不能为空");
        requireText(topic, "summary", path + ".summary 不能为空");
        requireScore(topic.get("score"), path + ".score");

        JsonNode tags = topic.get("tags");
        if (tags == null || !tags.isArray() || tags.isEmpty() || tags.size() > 10) {
            throw invalidAiResponse(path + ".tags 必须包含 1-10 个标签");
        }
        for (JsonNode tag : tags) {
            if (!tag.isTextual() || !StringUtils.hasText(tag.asText())) {
                throw invalidAiResponse(path + ".tags 只能包含非空字符串");
            }
        }

        JsonNode reasons = topic.get("scoreReasons");
        if (reasons == null || !reasons.isObject()) {
            throw invalidAiResponse(path + ".scoreReasons 必须是 JSON 对象");
        }
        for (String dimension : SCORE_DIMENSIONS) {
            JsonNode detail = reasons.get(dimension);
            String detailPath = path + ".scoreReasons." + dimension;
            if (detail == null || !detail.isObject()) {
                throw invalidAiResponse(detailPath + " 必须是 JSON 对象");
            }
            requireScore(detail.get("score"), detailPath + ".score");
            requireText(detail, "reason", detailPath + ".reason 不能为空");
        }
    }

    private String requireText(JsonNode object, String field, String message) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.asText())) {
            throw invalidAiResponse(message);
        }
        return value.asText();
    }

    private void requireScore(JsonNode value, String path) {
        if (value == null
                || !value.isIntegralNumber()
                || value.intValue() < 0
                || value.intValue() > 100) {
            throw invalidAiResponse(path + " 必须是 0-100 的整数");
        }
    }

    private AiServiceException invalidAiResponse(String reason) {
        return new AiServiceException("AI 服务响应格式无效：" + reason);
    }

    private JsonNode buildResponse(JsonNode upstreamResult, JsonNode topics, Long taskId, Long storyId) {
        ObjectNode response = ((ObjectNode) upstreamResult).deepCopy();
        response.put("taskId", taskId);
        response.put("storyId", storyId);
        return response;
    }

    private void markFailed(AiTask task, StoryProject story, String message) {
        LocalDateTime failedAt = LocalDateTime.now();
        task.setStatus(AiTaskStatus.FAILED);
        task.setErrorMessage(truncate(message, 1000));
        task.setUpdatedTime(failedAt);
        taskMapper.updateById(task);

        story.setStatus(StoryStatus.GENERATION_FAILED);
        story.setUpdatedTime(failedAt);
        storyMapper.updateById(story);
    }

    private String writeJson(JsonNode json) {
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化 AI 请求或响应", exception);
        }
    }

    private String firstText(String preferred, String fallback) {
        if (StringUtils.hasText(preferred)) {
            return preferred.trim();
        }
        return StringUtils.hasText(fallback) ? fallback.trim() : null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
