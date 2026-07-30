package com.storyforge.story;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.exception.ApiException;
import com.storyforge.common.validation.CreativeDirectionValidator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class StoryService {

    private final StoryProjectMapper storyMapper;
    private final ObjectMapper objectMapper;

    public StoryService(StoryProjectMapper storyMapper, ObjectMapper objectMapper) {
        this.storyMapper = storyMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public StoryResponse create(Long userId, CreateStoryRequest request) {
        LocalDateTime now = LocalDateTime.now();
        StoryProject story = new StoryProject();
        story.setUserId(userId);
        story.setTitle(request.title().trim());
        story.setGenre(CreativeDirectionValidator.genre(request.genre()));
        story.setAudience(CreativeDirectionValidator.audience(request.audience(), false));
        story.setKeywords(CreativeDirectionValidator.keywords(request.keywords()));
        story.setStatus(StoryStatus.DRAFT);
        story.setCreatedTime(now);
        story.setUpdatedTime(now);
        storyMapper.insert(story);
        return toResponse(story);
    }

    public List<StoryResponse> list(Long userId) {
        return storyMapper.selectList(
                        Wrappers.<StoryProject>lambdaQuery()
                                .eq(StoryProject::getUserId, userId)
                                .orderByDesc(StoryProject::getUpdatedTime)
                                .orderByDesc(StoryProject::getId)
                ).stream()
                .map(this::toResponse)
                .toList();
    }

    public StoryResponse get(Long userId, Long storyId) {
        return toResponse(requireOwned(userId, storyId));
    }

    @Transactional
    public StoryResponse selectTopic(Long userId, Long storyId, SelectTopicRequest request) {
        StoryProject story = requireOwned(userId, storyId);
        JsonNode generatedTopics = parseJson(story.getGeneratedTopics());
        if (generatedTopics == null || !generatedTopics.isArray()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TOPICS_NOT_GENERATED",
                    "请先生成故事选题"
            );
        }

        String requestedId = scalarText(request.topicId());
        JsonNode selected = null;
        for (JsonNode topic : generatedTopics) {
            JsonNode id = topic.get("id");
            if (id != null && requestedId.equals(scalarText(id))) {
                selected = topic;
                break;
            }
        }
        if (selected == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TOPIC_NOT_FOUND", "选题不存在");
        }

        story.setSelectedTopic(writeJson(selected));
        story.setStatus(StoryStatus.SELECTED);
        story.setUpdatedTime(LocalDateTime.now());
        storyMapper.updateById(story);
        return toResponse(story);
    }

    public StoryProject requireOwned(Long userId, Long storyId) {
        StoryProject story = storyMapper.selectById(storyId);
        if (story == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "STORY_NOT_FOUND", "故事不存在");
        }
        if (!story.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "STORY_FORBIDDEN", "无权访问该故事");
        }
        return story;
    }

    public StoryResponse toResponse(StoryProject story) {
        return new StoryResponse(
                story.getId(),
                story.getUserId(),
                story.getTitle(),
                story.getGenre(),
                story.getAudience(),
                story.getKeywords(),
                story.getStatus(),
                parseJson(story.getSelectedTopic()),
                parseJson(story.getGeneratedTopics()),
                story.getCreatedTime(),
                story.getUpdatedTime()
        );
    }

    private JsonNode parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中保存的 JSON 无效", exception);
        }
    }

    private String writeJson(JsonNode json) {
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("选题 JSON 无效", exception);
        }
    }

    private String scalarText(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOPIC_ID", "topicId 必须是数字或字符串");
        }
        return value.asText();
    }

}
