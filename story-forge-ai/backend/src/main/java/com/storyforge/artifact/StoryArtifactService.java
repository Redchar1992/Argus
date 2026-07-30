package com.storyforge.artifact;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.task.AiTask;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class StoryArtifactService {

    private final StoryArtifactMapper artifactMapper;
    private final ObjectMapper objectMapper;

    public StoryArtifactService(StoryArtifactMapper artifactMapper, ObjectMapper objectMapper) {
        this.artifactMapper = artifactMapper;
        this.objectMapper = objectMapper;
    }

    public StoryArtifact save(AiTask task, ArtifactInput input) {
        String type = ArtifactType.normalize(input.artifactType());
        if (input.content() == null || input.content().isNull()) {
            throw new IllegalArgumentException(type + " 产物 content 不能为空");
        }
        int version = input.versionNo() == null ? nextVersion(task.getStoryId(), type) : input.versionNo();
        if (version < 1) {
            throw new IllegalArgumentException("versionNo 必须大于 0");
        }

        StoryArtifact existing = findVersion(task.getStoryId(), type, version);
        if (existing != null) {
            if (!readJson(existing.getContentJson()).equals(input.content())) {
                throw new IllegalStateException(
                        "产物版本冲突: " + type + " version " + version
                );
            }
            return existing;
        }

        LocalDateTime now = LocalDateTime.now();
        StoryArtifact artifact = new StoryArtifact();
        artifact.setStoryId(task.getStoryId());
        artifact.setTaskId(task.getId());
        artifact.setArtifactType(type);
        artifact.setVersionNo(version);
        artifact.setStatus(normalizeStatus(input.status()));
        artifact.setContentJson(writeJson(input.content()));
        artifact.setPromptVersion(trimToLength(input.promptVersion(), 32));
        artifact.setModelName(trimToLength(input.modelName(), 100));
        artifact.setCreatedTime(now);
        artifact.setUpdatedTime(now);
        artifactMapper.insert(artifact);
        return artifact;
    }

    public StoryArtifact findLatest(Long storyId, String artifactType) {
        return artifactMapper.selectOne(
                Wrappers.<StoryArtifact>lambdaQuery()
                        .eq(StoryArtifact::getStoryId, storyId)
                        .eq(StoryArtifact::getArtifactType, ArtifactType.normalize(artifactType))
                        .orderByDesc(StoryArtifact::getVersionNo)
                        .last("LIMIT 1")
        );
    }

    public List<StoryArtifact> listVersions(Long storyId, String artifactType) {
        return artifactMapper.selectList(
                Wrappers.<StoryArtifact>lambdaQuery()
                        .eq(StoryArtifact::getStoryId, storyId)
                        .eq(StoryArtifact::getArtifactType, ArtifactType.normalize(artifactType))
                        .orderByAsc(StoryArtifact::getVersionNo)
        );
    }

    public JsonNode content(StoryArtifact artifact) {
        return artifact == null ? null : readJson(artifact.getContentJson());
    }

    private StoryArtifact findVersion(Long storyId, String type, int version) {
        return artifactMapper.selectOne(
                Wrappers.<StoryArtifact>lambdaQuery()
                        .eq(StoryArtifact::getStoryId, storyId)
                        .eq(StoryArtifact::getArtifactType, type)
                        .eq(StoryArtifact::getVersionNo, version)
        );
    }

    private int nextVersion(Long storyId, String type) {
        StoryArtifact latest = findLatest(storyId, type);
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private String normalizeStatus(String value) {
        String status = StringUtils.hasText(value) ? value.trim().toUpperCase() : "GENERATED";
        if (status.length() > 24) {
            throw new IllegalArgumentException("artifact status 最多 24 个字符");
        }
        return status;
    }

    private String trimToLength(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String writeJson(JsonNode content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法序列化故事产物", exception);
        }
    }

    private JsonNode readJson(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的故事产物 JSON 无效", exception);
        }
    }
}
