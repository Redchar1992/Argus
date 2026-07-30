package com.storyforge.chapter.service;

import java.time.LocalDateTime;
import java.util.List;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.storyforge.chapter.ChapterContentPolicy;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import com.storyforge.chapter.mapper.StoryChapterMapper;
import com.storyforge.chapter.mapper.StoryChapterVersionMapper;
import com.storyforge.chapter.vo.ChapterVersionCompareResponse;
import com.storyforge.chapter.vo.ChapterVersionResponse;
import com.storyforge.common.exception.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ChapterVersionService {
    private final StoryChapterVersionMapper versions;
    private final StoryChapterMapper chapters;
    private final ChapterSupport support;
    public ChapterVersionService(StoryChapterVersionMapper versions, StoryChapterMapper chapters, ChapterSupport support) {
        this.versions = versions; this.chapters = chapters; this.support = support;
    }

    public StoryChapterVersion requireInChapter(Long chapterId, Long versionId) {
        StoryChapterVersion version = versions.selectById(versionId);
        if (version == null || !chapterId.equals(version.getChapterId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CHAPTER_VERSION_NOT_FOUND", "章节版本不存在");
        }
        return version;
    }

    public StoryChapterVersion createAndAdvance(
            StoryChapter lockedChapter, String sourceType, String content, Long baseVersionId,
            Long aiTaskId, String idempotencyKey, String promptVersion, String modelName,
            JsonNode review, String changeSummary, Long createdBy, String chapterStatus
    ) {
        ChapterContentPolicy.requireValidLength(content);
        StoryChapterVersion existing = idempotencyKey == null ? null
                : versions.selectByIdempotencyKey(lockedChapter.getId(), idempotencyKey);
        if (existing != null) {
            if (!existing.getContentHash().equals(support.sha256(content))) {
                throw new IllegalStateException("版本幂等键对应了不同正文");
            }
            return existing;
        }
        StoryChapterVersion version = new StoryChapterVersion();
        version.setChapterId(lockedChapter.getId());
        version.setVersionNo(versions.selectMaxVersionNo(lockedChapter.getId()) + 1);
        version.setSourceType(sourceType);
        version.setContent(content);
        version.setContentHash(support.sha256(content));
        version.setBaseVersionId(baseVersionId);
        version.setAiTaskId(aiTaskId);
        version.setIdempotencyKey(idempotencyKey);
        version.setPromptVersion(trim(promptVersion, 32));
        version.setModelName(trim(modelName, 100));
        version.setReviewJson(review == null || review.isNull() ? null : support.write(review));
        version.setChangeSummary(trim(changeSummary, 65535));
        version.setCreatedBy(createdBy);
        version.setCreatedTime(LocalDateTime.now());
        try {
            versions.insert(version);
        } catch (DataIntegrityViolationException exception) {
            StoryChapterVersion raced = idempotencyKey == null ? null
                    : versions.selectByIdempotencyKey(lockedChapter.getId(), idempotencyKey);
            if (raced != null && raced.getContentHash().equals(version.getContentHash())) return raced;
            throw exception;
        }
        int count = wordCount(content);
        int updated = chapters.advanceVersion(lockedChapter.getId(), lockedChapter.getRowVersion(),
                version.getId(), chapterStatus, count);
        if (updated != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_VERSION_CONFLICT", "章节已被其他编辑更新，请刷新后重试");
        }
        lockedChapter.setCurrentVersionId(version.getId());
        lockedChapter.setStatus(chapterStatus);
        lockedChapter.setWordCount(count);
        lockedChapter.setRowVersion(lockedChapter.getRowVersion() + 1);
        return version;
    }

    public List<ChapterVersionResponse> list(Long chapterId) {
        return versions.selectList(Wrappers.<StoryChapterVersion>lambdaQuery()
                        .eq(StoryChapterVersion::getChapterId, chapterId)
                        .orderByDesc(StoryChapterVersion::getVersionNo))
                .stream().map(this::toResponse).toList();
    }

    public ChapterVersionCompareResponse compare(Long chapterId, Long fromId, Long toId) {
        StoryChapterVersion from = requireInChapter(chapterId, fromId);
        StoryChapterVersion to = requireInChapter(chapterId, toId);
        String left = from.getContent(); String right = to.getContent();
        int prefix = 0;
        int maxPrefix = Math.min(left.length(), right.length());
        while (prefix < maxPrefix && left.charAt(prefix) == right.charAt(prefix)) prefix++;
        int suffix = 0;
        int maxSuffix = Math.min(left.length() - prefix, right.length() - prefix);
        while (suffix < maxSuffix
                && left.charAt(left.length() - 1 - suffix) == right.charAt(right.length() - 1 - suffix)) suffix++;
        String fromChanged=left.substring(prefix, left.length() - suffix);
        String toChanged=right.substring(prefix, right.length() - suffix);
        var changes=support.mapper().createObjectNode();
        changes.put("commonPrefixLength",prefix);changes.put("commonSuffixLength",suffix);
        changes.put("fromText",fromChanged);changes.put("toText",toChanged);
        return new ChapterVersionCompareResponse(toResponse(from), toResponse(to), prefix, suffix,
                fromChanged, toChanged, changes);
    }

    public ChapterVersionResponse toResponse(StoryChapterVersion version) {
        if (version == null) return null;
        return new ChapterVersionResponse(version.getId(), version.getChapterId(), version.getVersionNo(),
                version.getSourceType(), version.getContent(), version.getContentHash(), version.getBaseVersionId(),
                version.getAiTaskId(), version.getPromptVersion(), version.getModelName(),
                support.read(version.getReviewJson()), version.getChangeSummary(), version.getCreatedBy(),
                version.getCreatedTime());
    }
    public int wordCount(String content) {
        return (int) content.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
    }
    private String trim(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
