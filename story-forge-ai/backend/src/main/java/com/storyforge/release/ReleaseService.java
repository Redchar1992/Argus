package com.storyforge.release;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.analytics.ProductAnalyticsService;
import com.storyforge.analytics.ProductEventNames;
import com.storyforge.chapter.ChapterStatus;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import com.storyforge.chapter.mapper.StoryChapterMapper;
import com.storyforge.chapter.mapper.StoryChapterVersionMapper;
import com.storyforge.artifact.ArtifactType;
import com.storyforge.artifact.StoryArtifact;
import com.storyforge.artifact.StoryArtifactService;
import com.storyforge.common.exception.ApiException;
import com.storyforge.report.FinalReportResponse;
import com.storyforge.report.FinalReportService;
import com.storyforge.story.StoryProject;
import com.storyforge.story.StoryProjectMapper;
import com.storyforge.story.StoryService;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReleaseService {
    private final StoryService stories;
    private final StoryChapterMapper chapters;
    private final StoryChapterVersionMapper versions;
    private final FinalReportService reports;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final StoryProjectMapper storyMapper;
    private final StoryArtifactService artifacts;
    private final ProductAnalyticsService analytics;

    public ReleaseService(StoryService stories, StoryChapterMapper chapters,
            StoryChapterVersionMapper versions, FinalReportService reports,
            ObjectMapper mapper, JdbcTemplate jdbc, StoryProjectMapper storyMapper,
            StoryArtifactService artifacts, ProductAnalyticsService analytics) {
        this.stories = stories;
        this.chapters = chapters;
        this.versions = versions;
        this.reports = reports;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.storyMapper = storyMapper;
        this.artifacts = artifacts;
        this.analytics = analytics;
    }

    @Transactional
    public ReleaseResponse create(Long userId, Long storyId, Long requestedReportId) {
        StoryProject story = stories.requireOwned(userId, storyId);
        // Serialize release numbering and snapshot capture per story.  The
        // unique constraint remains the final guard, but concurrent requests
        // now observe a single monotonically increasing release number.
        story = storyMapper.selectByIdForUpdate(storyId);
        FinalReportResponse report = reports.latest(userId, storyId);
        if (requestedReportId != null && !requestedReportId.equals(report.id())) {
            report = reports.list(userId, storyId).stream().filter(item -> requestedReportId.equals(item.id())).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FINAL_REPORT_NOT_FOUND", "终审报告不存在"));
        }
        List<StoryChapter> chapterList = chapters.selectList(Wrappers.<StoryChapter>lambdaQuery()
                .eq(StoryChapter::getStoryId, storyId).orderByAsc(StoryChapter::getChapterNo));
        if (chapterList.isEmpty() || chapterList.stream().anyMatch(chapter -> !ChapterStatus.APPROVED.equals(chapter.getStatus()) || chapter.getCurrentVersionId() == null)) {
            throw new ApiException(HttpStatus.CONFLICT, "RELEASE_CHAPTERS_NOT_READY", "只有全部批准的章节才能锁定正式版本");
        }
        ArrayNode snapshots = mapper.createArrayNode();
        StoryArtifact characterArtifact = artifacts.findLatest(storyId, ArtifactType.CHARACTER);
        StoryArtifact outlineArtifact = artifacts.findLatest(storyId, ArtifactType.OUTLINE);
        JsonNode characters = characterArtifact == null ? mapper.createArrayNode() : artifacts.content(characterArtifact);
        JsonNode outline = outlineArtifact == null ? mapper.createArrayNode() : artifacts.content(outlineArtifact);
        StringBuilder content = new StringBuilder(story.getTitle());
        content.append('\n').append(write(characters)).append('\n').append(write(outline));
        int wordCount = 0;
        for (StoryChapter chapter : chapterList) {
            StoryChapterVersion version = versions.selectById(chapter.getCurrentVersionId());
            if (version == null || !"APPROVED".equals(version.getSourceType())) {
                throw new ApiException(HttpStatus.CONFLICT, "RELEASE_APPROVED_VERSION_MISSING", "章节正式版本不存在");
            }
            ObjectNode snapshot = snapshots.addObject();
            snapshot.put("chapterNo", chapter.getChapterNo());
            snapshot.put("chapterId", chapter.getId());
            snapshot.put("versionId", version.getId());
            snapshot.put("title", chapter.getTitle() == null ? "第" + chapter.getChapterNo() + "章" : chapter.getTitle());
            snapshot.put("wordCount", version.getContent().codePointCount(0, version.getContent().length()));
            content.append('\n').append(version.getContent());
            wordCount += version.getContent().codePointCount(0, version.getContent().length());
        }
        String tags = write(report.report().path("suggestedTags"));
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(release_no),0) FROM story_release WHERE story_id=?", Integer.class, storyId);
        int releaseNo = (max == null ? 0 : max) + 1;
        String hash = sha256(content.toString());
        jdbc.update("""
                INSERT INTO story_release
                (story_id, release_no, title, summary, tags_json, outline_version_id, report_id,
                 chapter_versions_json, characters_json, outline_json, word_count, content_hash,
                 status, created_by, created_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'LOCKED', ?, CURRENT_TIMESTAMP)
                """, storyId, releaseNo, story.getTitle(), story.getSelectedTopic(), tags,
                outlineArtifact == null ? null : outlineArtifact.getId(), report.id(), write(snapshots),
                write(characters), write(outline), wordCount, hash, userId);
        Long id = jdbc.queryForObject("SELECT id FROM story_release WHERE story_id=? AND release_no=?", Long.class, storyId, releaseNo);
        ReleaseResponse created = get(userId, id);
        analytics.record(
                ProductEventNames.RELEASE_CREATED,
                userId,
                storyId,
                null,
                "release:" + id + ":created",
                java.util.Map.of(
                        "releaseNo", releaseNo,
                        "wordCount", wordCount
                )
        );
        return created;
    }

    public List<ReleaseResponse> list(Long userId, Long storyId) {
        stories.requireOwned(userId, storyId);
        return jdbc.query("SELECT * FROM story_release WHERE story_id=? ORDER BY release_no DESC", (rs, row) -> map(rs), storyId);
    }

    public ReleaseResponse get(Long userId, Long releaseId) {
        var rows = jdbc.query("SELECT * FROM story_release WHERE id=?", (rs, row) -> map(rs), releaseId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "正式版本不存在");
        ReleaseResponse release = rows.get(0);
        stories.requireOwned(userId, release.storyId());
        return release;
    }

    private ReleaseResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReleaseResponse(rs.getLong("id"), rs.getLong("story_id"), rs.getInt("release_no"),
                rs.getString("title"), rs.getString("summary"), read(rs.getString("tags_json")),
                rs.getObject("outline_version_id", Long.class), rs.getObject("report_id", Long.class),
                read(rs.getString("chapter_versions_json")), read(rs.getString("characters_json")),
                read(rs.getString("outline_json")), rs.getInt("word_count"), rs.getString("content_hash"),
                rs.getString("status"), timestamp(rs.getTimestamp("created_time")));
    }

    private LocalDateTime timestamp(Timestamp value) { return value == null ? null : value.toLocalDateTime(); }
    private JsonNode read(String value) { if (value == null) return mapper.createArrayNode(); try { return mapper.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException("正式版本 JSON 无效", e); } }
    private String write(JsonNode value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("正式版本 JSON 序列化失败", e); } }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
