package com.storyforge.report;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.ai.AiServiceClient;
import com.storyforge.ai.AiServiceException;
import com.storyforge.chapter.ChapterStatus;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import com.storyforge.chapter.mapper.StoryChapterMapper;
import com.storyforge.chapter.mapper.StoryChapterVersionMapper;
import com.storyforge.common.exception.ApiException;
import com.storyforge.cost.AiCreditService;
import com.storyforge.story.StoryProject;
import com.storyforge.story.StoryProjectMapper;
import com.storyforge.story.StoryService;
import com.storyforge.prompt.PromptResolver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinalReportService {
    private static final String DISCLAIMER = "综合分仅表示系统按当前文本和规则得出的内容评估，不代表真实收益保证。";

    private final StoryService stories;
    private final StoryChapterMapper chapterMapper;
    private final StoryChapterVersionMapper versionMapper;
    private final ObjectMapper mapper;
    private final AiServiceClient ai;
    private final JdbcTemplate jdbc;
    private final AiCreditService credits;
    private final int maxReviewChars;
    private final PromptResolver prompts;
    private final StoryProjectMapper storyMapper;

    public FinalReportService(StoryService stories,
            StoryChapterMapper chapterMapper, StoryChapterVersionMapper versionMapper,
            ObjectMapper mapper, AiServiceClient ai, JdbcTemplate jdbc, AiCreditService credits,
            @Value("${app.ai.final-review-max-chars:500000}") int maxReviewChars,
            PromptResolver prompts, StoryProjectMapper storyMapper) {
        this.stories = stories;
        this.chapterMapper = chapterMapper;
        this.versionMapper = versionMapper;
        this.mapper = mapper;
        this.ai = ai;
        this.jdbc = jdbc;
        this.credits = credits;
        this.maxReviewChars = Math.max(10_000, maxReviewChars);
        this.prompts = prompts;
        this.storyMapper = storyMapper;
    }

    @Transactional
    public FinalReportResponse run(Long userId, Long storyId) {
        StoryProject story = stories.requireOwned(userId, storyId);
        story = storyMapper.selectByIdForUpdate(storyId);
        List<StoryChapter> chapters = chapterMapper.selectList(Wrappers.<StoryChapter>lambdaQuery()
                .eq(StoryChapter::getStoryId, storyId).orderByAsc(StoryChapter::getChapterNo));
        if (chapters.isEmpty()) {
            throw conflict("FINAL_REVIEW_NO_CHAPTERS", "请先完成至少一章正文");
        }
        ObjectNode request = mapper.createObjectNode();
        request.put("storyTitle", story.getTitle());
        request.put("genre", story.getGenre());
        request.put("targetAudience", story.getAudience());
        ArrayNode chapterPayload = request.putArray("chapters");
        int wordCount = 0;
        int reviewChars = 0;
        for (StoryChapter chapter : chapters) {
            if (!ChapterStatus.APPROVED.equals(chapter.getStatus()) || chapter.getCurrentVersionId() == null) {
                throw conflict("FINAL_REVIEW_CHAPTER_NOT_APPROVED", "请先批准全部章节，再执行全书终审");
            }
            StoryChapterVersion version = versionMapper.selectById(chapter.getCurrentVersionId());
            if (version == null || !"APPROVED".equals(version.getSourceType())) {
                throw conflict("FINAL_REVIEW_APPROVED_VERSION_MISSING", "章节缺少正式批准版本");
            }
            ObjectNode item = chapterPayload.addObject();
            item.put("chapterNo", chapter.getChapterNo());
            item.put("title", chapter.getTitle() == null ? "第" + chapter.getChapterNo() + "章" : chapter.getTitle());
            item.put("content", version.getContent());
            reviewChars += version.getContent().length();
            if (reviewChars > maxReviewChars) {
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FINAL_REVIEW_TOO_LARGE", "全书正文超过终审长度限制，请分段处理后再试");
            }
            wordCount += version.getContent().codePointCount(0, version.getContent().length());
        }
        request.set("characters", rowsAsJson("SELECT subject_name AS name, fact_value AS factValue FROM story_fact WHERE story_id=? AND fact_type='CHARACTER'", storyId));
        request.set("canonFacts", rowsAsJson("SELECT fact_key AS factKey, fact_type AS factType, subject_name AS subject, predicate_name AS predicate, fact_value AS factValue, visibility, source_chapter_no AS sourceChapter FROM story_fact WHERE story_id=? AND status='ACTIVE'", storyId));
        request.set("unresolvedThreads", rowsAsJson("SELECT thread_key AS threadKey, description, status, introduced_chapter_no AS introducedChapter FROM story_plot_thread WHERE story_id=? AND status <> 'RESOLVED'", storyId));
        request.set("foreshadowingLedger", rowsAsJson("SELECT foreshadow_key AS foreshadowKey, setup_text AS setup, setup_chapter_no AS setupChapter, payoff_plan AS payoffPlan, status FROM story_foreshadowing WHERE story_id=?", storyId));
        PromptResolver.Selection prompt = prompts.resolve(userId, "final_review", "final_review_v1");
        request.put("promptVersion", prompt.versionLabel());
        if (prompt.systemPrompt() != null) request.put("promptSystem", prompt.systemPrompt());

        long started = System.currentTimeMillis();
        String contentHash = sha256(write(request));
        FinalReportResponse existing = list(userId, storyId).stream()
                .filter(item -> contentHash.equals(item.contentHash()))
                .findFirst()
                .orElse(null);
        if (existing != null) return existing;
        String freezeKey = "final-review:freeze:" + storyId + ":" + contentHash;
        String settleKey = "final-review:settle:" + storyId + ":" + contentHash;
        credits.freeze(userId, null, freezeKey, 30, "全书终审预冻结");
        JsonNode report = ai.finalReview(request);
        validateReportContract(report);
        report = normalizeReport(report);
        int versionNo = nextVersion(storyId);
        String reportJson = write(report);
        String modelName = report.path("modelName").asText("final-review");
        String promptVersion = prompt.versionLabel();
        jdbc.update("""
                INSERT INTO story_final_report
                (story_id, report_version, status, report_json, total_score, level, content_hash,
                 word_count,
                 prompt_version, model_name, created_by, created_time)
                VALUES (?, ?, 'READY', ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, storyId, versionNo, reportJson, report.path("total").asInt(),
                report.path("level").asText(), contentHash, wordCount, promptVersion, modelName, userId);
        Long id = jdbc.queryForObject("SELECT id FROM story_final_report WHERE story_id=? AND report_version=?", Long.class, storyId, versionNo);
        long duration = System.currentTimeMillis() - started;
        long inputTokens = Math.max(1, request.toString().length() / 4);
        long outputTokens = Math.max(1, reportJson.length() / 4);
        jdbc.update("""
                INSERT INTO ai_model_usage
                (story_id, user_id, agent_type, provider, model_name, prompt_key, prompt_version,
                 input_tokens, output_tokens, estimated_cost, actual_cost, cost_status,
                 duration_ms, success, created_time)
                VALUES (?, ?, 'FINAL_REVIEW', 'ai-service', ?, 'final_review', ?, ?, ?, ?, ?, 'ESTIMATED', ?, TRUE, CURRENT_TIMESTAMP)
                """, storyId, userId, modelName, prompt.version(), inputTokens, outputTokens,
                (inputTokens + outputTokens) / 100000.0, (inputTokens + outputTokens) / 100000.0, duration);
        credits.settleFrozen(userId, null, freezeKey, settleKey, 30, 30, "全书终审");
        return response(id, storyId, versionNo, report, wordCount, contentHash, promptVersion, modelName);
    }

    public FinalReportResponse latest(Long userId, Long storyId) {
        stories.requireOwned(userId, storyId);
        return list(userId, storyId).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "FINAL_REPORT_NOT_FOUND", "尚未生成全书终审报告"));
    }

    public List<FinalReportResponse> list(Long userId, Long storyId) {
        stories.requireOwned(userId, storyId);
        return jdbc.query("SELECT * FROM story_final_report WHERE story_id=? ORDER BY report_version DESC", (rs, row) -> {
            JsonNode report = read(rs.getString("report_json"));
            return response(rs.getLong("id"), storyId, rs.getInt("report_version"), report,
                    rs.getInt("word_count"), rs.getString("content_hash"), rs.getString("prompt_version"), rs.getString("model_name"), rs.getTimestamp("created_time").toLocalDateTime());
        }, storyId);
    }

    private FinalReportResponse response(Long id, Long storyId, int versionNo, JsonNode report,
            int wordCount, String hash, String promptVersion, String modelName) {
        return response(id, storyId, versionNo, report, wordCount, hash, promptVersion, modelName, LocalDateTime.now());
    }

    private FinalReportResponse response(Long id, Long storyId, int versionNo, JsonNode report,
            int wordCount, String hash, String promptVersion, String modelName, LocalDateTime created) {
        return new FinalReportResponse(id, storyId, versionNo, "READY", report,
                report.path("total").asInt(), report.path("level").asText(), wordCount, hash,
                promptVersion, modelName, created);
    }

    private int nextVersion(Long storyId) {
        Integer result = jdbc.queryForObject("SELECT COALESCE(MAX(report_version),0)+1 FROM story_final_report WHERE story_id=?", Integer.class, storyId);
        return result == null ? 1 : result;
    }

    private ArrayNode rowsAsJson(String sql, Long storyId) {
        ArrayNode array = mapper.createArrayNode();
        jdbc.queryForList(sql, storyId).forEach(row -> {
            ObjectNode node = array.addObject();
            row.forEach((key, value) -> {
                if (value instanceof BigDecimal decimal) node.put(key, decimal);
                else if (value instanceof Number number) node.put(key, number.longValue());
                else if (value instanceof Boolean bool) node.put(key, bool);
                else if (value != null) node.put(key, String.valueOf(value));
            });
        });
        return array;
    }

    private void validateReportContract(JsonNode report) {
        if (report == null || !report.isObject()) {
            throw new AiServiceException("AI 终审响应必须是 JSON 对象");
        }
        for (String section : List.of("contentQuality", "hitPotential", "shortDramaAdaptation")) {
            JsonNode value = report.get(section);
            if (value == null || !value.isObject() || !value.path("score").canConvertToInt()
                    || value.path("score").asInt() < 0 || value.path("score").asInt() > 100
                    || !value.path("summary").isTextual() || value.path("summary").asText().isBlank()) {
                throw new AiServiceException("AI 终审响应缺少有效评分区块: " + section);
            }
        }
        for (String list : List.of("criticalIssues", "normalIssues", "suggestedTitles")) {
            if (!report.path(list).isArray()) {
                throw new AiServiceException("AI 终审响应缺少数组: " + list);
            }
        }
        if (report.path("suggestedTitles").isEmpty()) {
            throw new AiServiceException("AI 终审响应至少需要一个推荐标题");
        }
        validateIssues(report.path("criticalIssues"));
        validateIssues(report.path("normalIssues"));
    }

    private void validateIssues(JsonNode issues) {
        for (JsonNode issue : issues) {
            if (!issue.isObject()
                    || !issue.path("issueType").isTextual()
                    || !issue.path("severity").isTextual()
                    || !issue.path("title").isTextual()
                    || !issue.path("description").isTextual()
                    || !issue.path("suggestedFix").isTextual()
                    || !issue.path("evidence").isArray()
                    || issue.path("evidence").isEmpty()
                    || !issue.path("affectedChapters").isArray()
                    || issue.path("affectedChapters").isEmpty()) {
                throw new AiServiceException("AI 终审响应包含无效问题条目");
            }
        }
    }

    private JsonNode normalizeReport(JsonNode report) {
        ObjectNode normalized = report != null && report.isObject() ? ((ObjectNode) report).deepCopy() : mapper.createObjectNode();
        int content = clamp(normalized.path("contentQuality").path("score").asInt(0));
        int hit = clamp(normalized.path("hitPotential").path("score").asInt(0));
        int drama = clamp(normalized.path("shortDramaAdaptation").path("score").asInt(0));
        int total = Math.round(content * .4f + hit * .4f + drama * .2f);
        normalized.put("total", total);
        normalized.put("level", total >= 90 ? "S" : total >= 80 ? "A" : total >= 70 ? "B" : total >= 60 ? "C" : "D");
        if (!normalized.hasNonNull("disclaimer")) normalized.put("disclaimer", DISCLAIMER);
        return normalized;
    }

    private int clamp(int value) { return Math.max(0, Math.min(100, value)); }
    private JsonNode read(String value) { try { return mapper.readTree(value); } catch (JsonProcessingException e) { throw new IllegalStateException("终审报告 JSON 无效", e); } }
    private String write(JsonNode value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalStateException("终审报告序列化失败", e); } }
    private String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
    private ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
}
