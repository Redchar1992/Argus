package com.storyforge.chapter.service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.artifact.ArtifactType;
import com.storyforge.artifact.StoryArtifact;
import com.storyforge.artifact.StoryArtifactService;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.common.exception.ApiException;
import com.storyforge.story.StoryProject;
import com.storyforge.story.StoryContentMode;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChapterContextAssembler {
    private final StoryArtifactService artifacts;
    private final JdbcTemplate jdbc;
    private final ChapterSupport support;
    @Value("${app.ai.chapter-context-max-chars:40000}")
    private int maxContextChars = 40_000;
    public ChapterContextAssembler(StoryArtifactService artifacts, JdbcTemplate jdbc, ChapterSupport support) {
        this.artifacts = artifacts; this.jdbc = jdbc; this.support = support;
    }

    public ObjectNode assemble(StoryProject story, StoryChapter chapter, int targetLength) {
        ObjectNode payload = support.mapper().createObjectNode();
        payload.put("storyTitle", story.getTitle());
        payload.put("genre", story.getGenre());
        payload.put("targetAudience", story.getAudience() == null ? "" : story.getAudience());
        payload.put("contentMode", StoryContentMode.parse(story.getContentMode()).name());
        payload.put("targetChapterCount", story.getTargetChapterCount() == null ? 10 : story.getTargetChapterCount());
        payload.put("targetTotalWords", story.getTargetTotalWords() == null ? 30_000 : story.getTargetTotalWords());
        payload.put("chapterTargetWords", story.getChapterTargetWords() == null ? 1_800 : story.getChapterTargetWords());
        payload.put("viewpoint", story.getViewpoint() == null ? "THIRD_LIMITED" : story.getViewpoint());
        JsonNode style = story.getStyleProfile() == null ? null : support.read(story.getStyleProfile());
        payload.set("styleProfile", style == null || !style.isObject()
                ? support.mapper().createObjectNode() : style);
        JsonNode finalArtifact = content(story.getId(), ArtifactType.WORKFLOW_FINAL);
        JsonNode characters = child(finalArtifact, "characters");
        if (characters == null) characters = content(story.getId(), ArtifactType.CHARACTER);
        payload.set("characters", unwrapArray(characters, "characters"));
        JsonNode outline = child(finalArtifact, "outline");
        if (outline == null) outline = content(story.getId(), ArtifactType.OUTLINE);
        // WORKFLOW_FINAL stores the approved outline directly as an array, while
        // standalone OUTLINE artifacts use {"nodes": [...]}. Accept both wire
        // shapes so chapter generation consumes the real workflow output.
        JsonNode outlineNodes = unwrapArray(outline, "nodes");
        ArrayNode currentOutlineNodes = currentOutlineNodes(outlineNodes, chapter.getChapterNo());
        // Keep outlineNodes as the backwards-compatible wire name while making the
        // bounded, chapter-specific meaning explicit for newer workers.
        payload.set("outlineNodes", currentOutlineNodes.deepCopy());
        payload.set("currentOutlineNodes", currentOutlineNodes);
        payload.set("canonFacts", rows("""
                SELECT fact_key AS factKey, fact_type AS factType, subject_name AS subject,
                       predicate_name AS predicate, fact_value AS factValue, visibility, locked,
                       source_chapter_no AS sourceChapter, status
                FROM story_fact WHERE story_id=? AND status='ACTIVE' ORDER BY id
                """, story.getId()));
        payload.set("relationshipStates", rows("""
                SELECT relationship_key AS relationshipKey, character_a AS characterA,
                       character_b AS characterB, relation_name AS relation, trust_score AS trust,
                       conflict_score AS conflict, public_status AS publicStatus,
                       private_status AS privateStatus, updated_at_chapter_no AS updatedAtChapter
                FROM story_relationship WHERE story_id=? ORDER BY id
                """, story.getId()));
        payload.set("recentSummaries", rows("""
                SELECT c.chapter_no AS chapterNo, s.summary, s.main_events_json AS mainEventsJson,
                       s.character_changes_json AS characterChangesJson,
                       s.opened_threads_json AS openedThreadsJson,
                       s.resolved_threads_json AS resolvedThreadsJson, s.ending_hook AS endingHook
                FROM story_chapter_summary s JOIN story_chapter c ON c.id=s.chapter_id
                WHERE c.story_id=? ORDER BY c.chapter_no DESC LIMIT 3
                """, story.getId()));
        payload.set("unresolvedThreads", rows("""
                SELECT thread_key AS threadKey, description, introduced_chapter_no AS introducedChapter,
                       expected_payoff_chapter_no AS expectedPayoffChapter, status, clues_json AS cluesJson
                FROM story_plot_thread WHERE story_id=? AND status<>'RESOLVED' ORDER BY id
                """, story.getId()));
        payload.set("foreshadowingLedger", rows("""
                SELECT foreshadow_key AS foreshadowKey, setup_text AS setup,
                       setup_chapter_no AS setupChapter, payoff_plan AS payoffPlan,
                       payoff_chapter_no AS payoffChapter, status
                FROM story_foreshadowing WHERE story_id=? AND status<>'PAID_OFF' ORDER BY id
                """, story.getId()));
        boundContext(payload);
        payload.put("targetLength", targetLength);
        payload.put("chapterNo", chapter.getChapterNo());
        payload.put("contextSnapshotHash", support.sha256(support.write(payload)));
        return payload;
    }

    private void boundContext(ObjectNode payload) {
        ObjectNode omitted = support.mapper().createObjectNode();
        cap(payload, "characters", 24, omitted);
        cap(payload, "canonFacts", 80, omitted);
        cap(payload, "relationshipStates", 80, omitted);
        cap(payload, "recentSummaries", 3, omitted);
        cap(payload, "unresolvedThreads", 60, omitted);
        cap(payload, "foreshadowingLedger", 60, omitted);

        String[] trimOrder = {"foreshadowingLedger", "unresolvedThreads", "relationshipStates", "canonFacts", "characters"};
        boolean trimmed = false;
        while (support.write(payload).length() > Math.max(10_000, maxContextChars)) {
            boolean removed = false;
            for (String field : trimOrder) {
                JsonNode node = payload.get(field);
                if (node != null && node.isArray() && node.size() > 1) {
                    ((ArrayNode) node).remove(0);
                    omitted.put(field, omitted.path(field).asInt(0) + 1);
                    removed = true;
                    trimmed = true;
                    break;
                }
            }
            if (!removed) break;
        }
        if (trimmed || !omitted.isEmpty()) {
            payload.set("contextOmitted", omitted);
        }
    }

    private void cap(ObjectNode payload, String field, int max, ObjectNode omitted) {
        JsonNode node = payload.get(field);
        if (node == null || !node.isArray()) return;
        ArrayNode array = (ArrayNode) node;
        int removed = 0;
        while (array.size() > max) {
            array.remove(0);
            removed++;
        }
        if (removed > 0) omitted.put(field, removed);
    }

    private ArrayNode currentOutlineNodes(JsonNode outlineNodes, int chapterNo) {
        int start = (chapterNo - 1) * 2;
        int end = start + 2;
        if (chapterNo < 1 || outlineNodes == null || !outlineNodes.isArray() || outlineNodes.size() < end) {
            int available = outlineNodes != null && outlineNodes.isArray() ? outlineNodes.size() : 0;
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_OUTLINE_NODES_UNAVAILABLE",
                    "大纲缺少第 " + chapterNo + " 章对应的两个节点（需要索引 " + start + " 和 "
                            + (end - 1) + "，当前节点数 " + available + "）");
        }
        ArrayNode selected = support.mapper().createArrayNode();
        selected.add(outlineNodes.get(start).deepCopy());
        selected.add(outlineNodes.get(start + 1).deepCopy());
        return selected;
    }

    private JsonNode content(Long storyId, String type) {
        StoryArtifact artifact = artifacts.findLatest(storyId, type);
        return artifacts.content(artifact);
    }
    private JsonNode child(JsonNode node, String field) {
        return node != null && node.isObject() ? node.get(field) : null;
    }
    private ArrayNode unwrapArray(JsonNode node, String field) {
        if (node == null) return support.mapper().createArrayNode();
        if (node.isArray()) return (ArrayNode) node;
        JsonNode nested = node.get(field);
        return nested != null && nested.isArray() ? (ArrayNode) nested : support.mapper().createArrayNode();
    }
    private ArrayNode rows(String sql, Long storyId) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, storyId);
        ArrayNode result = support.mapper().createArrayNode();
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((key, value) -> normalized.put(canonicalKey(key), value));
            ObjectNode node = support.mapper().valueToTree(normalized);
            expandJson(node, "mainEventsJson", "mainEvents");
            expandJson(node, "characterChangesJson", "characterChanges");
            expandJson(node, "openedThreadsJson", "openedThreads");
            expandJson(node, "resolvedThreadsJson", "resolvedThreads");
            expandJson(node, "cluesJson", "knownClues");
            result.add(node);
        }
        return result;
    }

    private String canonicalKey(String key) {
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "factkey" -> "factKey";
            case "facttype" -> "factType";
            case "factvalue" -> "value";
            case "sourcechapter" -> "sourceChapter";
            case "relationshipkey" -> "relationshipKey";
            case "charactera" -> "characterA";
            case "characterb" -> "characterB";
            case "publicstatus" -> "publicStatus";
            case "privatestatus" -> "privateStatus";
            case "updatedatchapter" -> "updatedAtChapter";
            case "chapterno" -> "chapterNo";
            case "maineventsjson" -> "mainEventsJson";
            case "characterchangesjson" -> "characterChangesJson";
            case "openedthreadsjson" -> "openedThreadsJson";
            case "resolvedthreadsjson" -> "resolvedThreadsJson";
            case "endinghook" -> "endingHook";
            case "threadkey" -> "threadKey";
            case "introducedchapter" -> "introducedChapter";
            case "expectedpayoffchapter" -> "expectedPayoffChapter";
            case "cluesjson" -> "cluesJson";
            case "foreshadowkey" -> "foreshadowKey";
            case "setupchapter" -> "setupChapter";
            case "payoffplan" -> "payoffPlan";
            case "payoffchapter" -> "payoffChapter";
            default -> key.toLowerCase(Locale.ROOT);
        };
    }
    private void expandJson(ObjectNode node, String source, String target) {
        JsonNode raw = node.remove(source);
        if (raw == null || raw.isNull()) return;
        JsonNode parsed = raw.isTextual() ? support.read(raw.asText()) : raw;
        if (parsed != null) node.set(target, parsed);
    }
}
