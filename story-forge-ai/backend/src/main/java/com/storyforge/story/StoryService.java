package com.storyforge.story;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.analytics.ProductAnalyticsService;
import com.storyforge.analytics.ProductEventNames;
import com.storyforge.common.exception.ApiException;
import com.storyforge.common.validation.CreativeDirectionValidator;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import com.storyforge.task.AiTaskStatus;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class StoryService {

    private final StoryProjectMapper storyMapper;
    private final AiTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final ProductAnalyticsService analytics;

    public StoryService(
            StoryProjectMapper storyMapper,
            AiTaskMapper taskMapper,
            ObjectMapper objectMapper,
            ProductAnalyticsService analytics
    ) {
        this.storyMapper = storyMapper;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.analytics = analytics;
    }

    @Transactional
    public StoryResponse create(Long userId, CreateStoryRequest request) {
        LocalDateTime now = LocalDateTime.now();
        StoryContentMode contentMode = StoryContentMode.parse(request.contentMode());
        int targetChapterCount = request.targetChapterCount() == null
                ? contentMode.defaultChapterCount() : request.targetChapterCount();
        if (targetChapterCount < contentMode.minChapterCount()) {
            String message = contentMode == StoryContentMode.SHORT_STORY
                    ? "短故事目标章节数至少为 3"
                    : "小说目标章节数至少为 20";
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    contentMode == StoryContentMode.SHORT_STORY
                            ? "SHORT_STORY_CHAPTER_COUNT_TOO_SMALL"
                            : "NOVEL_CHAPTER_COUNT_TOO_SMALL",
                    message
            );
        }
        if (contentMode == StoryContentMode.SHORT_STORY && targetChapterCount > 10) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SHORT_STORY_CHAPTER_LIMIT",
                    "短故事目标章节数不能超过 10；长篇内容请切换为小说模式");
        }
        if (contentMode == StoryContentMode.NOVEL && targetChapterCount < 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOVEL_CHAPTER_COUNT_TOO_SMALL",
                    "小说目标章节数至少为 20");
        }
        int chapterTargetWords = request.chapterTargetWords() == null
                ? contentMode.defaultChapterWords() : request.chapterTargetWords();
        int targetTotalWords = request.targetTotalWords() == null
                ? Math.max(contentMode.defaultTotalWords(), targetChapterCount * chapterTargetWords)
                : request.targetTotalWords();
        String viewpoint = StringUtils.hasText(request.viewpoint())
                ? request.viewpoint().trim() : "THIRD_LIMITED";
        String styleProfile = serializeStyleProfile(request.styleProfile());
        StoryProject story = new StoryProject();
        story.setUserId(userId);
        story.setTitle(request.title().trim());
        story.setGenre(CreativeDirectionValidator.genre(request.genre()));
        story.setAudience(CreativeDirectionValidator.audience(request.audience(), false));
        story.setKeywords(CreativeDirectionValidator.keywords(request.keywords()));
        story.setContentMode(contentMode.name());
        story.setTargetChapterCount(targetChapterCount);
        story.setTargetTotalWords(targetTotalWords);
        story.setChapterTargetWords(chapterTargetWords);
        story.setViewpoint(viewpoint);
        story.setStyleProfile(styleProfile);
        story.setStatus(StoryStatus.DRAFT);
        story.setCreatedTime(now);
        story.setUpdatedTime(now);
        storyMapper.insert(story);
        analytics.record(
                ProductEventNames.STORY_CREATED,
                userId,
                story.getId(),
                null,
                "story:" + story.getId() + ":created",
                java.util.Map.of("genre", story.getGenre())
        );
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
        StoryProject story = requireOwnedForUpdate(userId, storyId);
        if (taskMapper.selectLatestWorkflowTaskId(storyId) != null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "WORKFLOW_TOPIC_LOCKED",
                    "工作流已绑定选题，不能重新选择"
            );
        }
        JsonNode selected = findGeneratedTopic(story, request.topicId());
        applySelection(story, selected, StoryStatus.SELECTED);
        analytics.record(
                ProductEventNames.TOPIC_SELECTED,
                userId,
                storyId,
                null,
                "story:" + storyId + ":topic-selected",
                java.util.Map.of("topicId", scalarText(request.topicId()))
        );
        return toResponse(story);
    }

    /**
     * Repairs the story projection after a concurrent START request loses the
     * task idempotency-key race. The persisted winning task is authoritative.
     */
    @Transactional
    public void restoreWorkflowSelection(
            Long userId,
            Long storyId,
            Long taskId,
            JsonNode winningTopic
    ) {
        StoryProject story = requireOwnedForUpdate(userId, storyId);
        AiTask winningStart = taskMapper.selectById(taskId);
        if (winningStart == null
                || !storyId.equals(winningStart.getStoryId())
                || !userId.equals(winningStart.getUserId())
                || !"STORY_WORKFLOW".equals(winningStart.getTaskType())) {
            throw new IllegalStateException("原始工作流任务与故事不一致");
        }
        Long latestTaskId = taskMapper.selectLatestWorkflowTaskId(storyId);
        if (latestTaskId == null) {
            throw new IllegalStateException("故事缺少最新工作流任务");
        }
        AiTask statusAuthority = taskId.equals(latestTaskId)
                ? winningStart
                : taskMapper.selectById(latestTaskId);
        if (statusAuthority == null) {
            throw new IllegalStateException("故事缺少最新工作流任务");
        }
        applySelection(story, winningTopic, storyStatusFor(statusAuthority));
    }

    private JsonNode findGeneratedTopic(StoryProject story, JsonNode topicId) {
        JsonNode generatedTopics = parseJson(story.getGeneratedTopics());
        if (generatedTopics == null || !generatedTopics.isArray()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TOPICS_NOT_GENERATED",
                    "请先生成故事选题"
            );
        }

        String requestedId = scalarText(topicId);
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
        return selected;
    }

    private void applySelection(StoryProject story, JsonNode selected, String status) {
        story.setSelectedTopic(writeJson(selected));
        story.setStatus(status);
        story.setUpdatedTime(LocalDateTime.now());
        storyMapper.updateById(story);
    }

    private String storyStatusFor(AiTask task) {
        return switch (task.getStatus()) {
            case AiTaskStatus.WAITING -> StoryStatus.SELECTED;
            case AiTaskStatus.RUNNING -> StoryStatus.WORKFLOW_RUNNING;
            case AiTaskStatus.REVIEW_REQUIRED -> StoryStatus.WORKFLOW_REVIEW_REQUIRED;
            case AiTaskStatus.SUCCESS -> StoryStatus.WORKFLOW_COMPLETED;
            case AiTaskStatus.FAILED -> StoryStatus.WORKFLOW_FAILED;
            default -> throw new IllegalStateException("不支持的工作流任务状态: " + task.getStatus());
        };
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

    private StoryProject requireOwnedForUpdate(Long userId, Long storyId) {
        StoryProject story = storyMapper.selectByIdForUpdate(storyId);
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
                contentMode(story).name(),
                valueOr(story.getTargetChapterCount(), contentMode(story).defaultChapterCount()),
                valueOr(story.getTargetTotalWords(), contentMode(story).defaultTotalWords()),
                valueOr(story.getChapterTargetWords(), contentMode(story).defaultChapterWords()),
                StringUtils.hasText(story.getViewpoint()) ? story.getViewpoint() : "THIRD_LIMITED",
                parseJson(story.getStyleProfile()),
                story.getCreatedTime(),
                story.getUpdatedTime()
        );
    }

    private StoryContentMode contentMode(StoryProject story) {
        return StoryContentMode.parse(story.getContentMode());
    }

    private int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
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

    private String serializeStyleProfile(JsonNode styleProfile) {
        if (styleProfile == null || styleProfile.isNull()) return null;
        if (!styleProfile.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STYLE_PROFILE_INVALID",
                    "文风配置必须是 JSON 对象");
        }
        String value = writeJson(styleProfile);
        if (value.length() > 10_000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "STYLE_PROFILE_TOO_LARGE",
                    "文风配置不能超过 10000 个字符");
        }
        return value;
    }

    private String scalarText(JsonNode value) {
        if (value == null || value.isNull() || value.isContainerNode()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOPIC_ID", "topicId 必须是数字或字符串");
        }
        return value.asText();
    }

}
