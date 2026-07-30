package com.storyforge.chapter.service;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class StoryMemoryService {
    private static final Pattern FACT_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");
    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "READER_ONLY", "CHARACTER_PRIVATE", "UNKNOWN");
    private static final Set<String> FACT_STATUSES = Set.of("ACTIVE", "INACTIVE", "SUPERSEDED");
    private static final Set<String> THREAD_STATUSES = Set.of("OPEN", "DORMANT", "RESOLVED");
    private static final Set<String> FORESHADOW_STATUSES = Set.of("SETUP", "PAID_OFF", "ABANDONED");
    private final JdbcTemplate jdbc;
    private final ChapterSupport support;
    public StoryMemoryService(JdbcTemplate jdbc, ChapterSupport support) { this.jdbc=jdbc; this.support=support; }

    public void persistApproval(StoryChapter chapter, StoryChapterVersion version, JsonNode summary, JsonNode memory) {
        validateApproval(summary, memory);
        persistSummary(chapter, version, summary);
        if (memory == null || !memory.isObject()) return;
        for (JsonNode fact : array(memory, "newFacts")) upsertFact(chapter, fact);
        for (JsonNode change : array(memory, "characterStateChanges")) upsertCharacterState(chapter, change);
        for (JsonNode relation : array(memory, "changedRelationships")) upsertRelationship(chapter, relation);
        for (JsonNode thread : array(memory, "openedThreads")) upsertThread(chapter, thread, "OPEN");
        for (JsonNode thread : array(memory, "updatedThreads")) upsertThread(chapter, thread, text(thread, "status", "OPEN"));
        for (JsonNode resolved : array(memory, "resolvedThreads")) resolveThread(chapter, resolved);
        for (JsonNode item : array(memory, "newForeshadowing")) upsertForeshadow(chapter, item);
        for (JsonNode paid : array(memory, "paidOffForeshadowing")) payOffForeshadow(chapter, paid);
    }

    void validateApproval(JsonNode summary, JsonNode memory) {
        if (summary != null && !summary.isNull()) {
            if (!summary.isObject() && !summary.isTextual()) invalid("summary", "必须是对象或文本");
            bounded(summary.isTextual() ? summary.asText() : text(summary, "summary", ""), 20_000,
                    "summary.summary", false);
            if (summary.isObject()) bounded(text(summary, "endingHook", null), 20_000,
                    "summary.endingHook", false);
        }
        if (memory == null || memory.isNull()) return;
        if (!memory.isObject()) invalid("memoryUpdate", "必须是对象");
        validateArray(memory, "newFacts", 30, this::validateFact);
        validateArray(memory, "characterStateChanges", 20, this::validateCharacterState);
        validateArray(memory, "changedRelationships", 20, this::validateRelationship);
        validateArray(memory, "openedThreads", 20, this::validateThread);
        validateArray(memory, "updatedThreads", 20, this::validateThread);
        validateKeyArray(memory, "resolvedThreads", 20, "threadKey");
        validateArray(memory, "newForeshadowing", 20, this::validateForeshadow);
        validateKeyArray(memory, "paidOffForeshadowing", 20, "foreshadowKey");
    }

    private void validateFact(JsonNode fact, String path) {
        object(fact, path);
        String key = text(fact, "factKey", null);
        if (key != null) bounded(key, 128, path + ".factKey", true);
        String type = upper(text(fact, "factType", "OTHER"));
        if (!FACT_TYPE.matcher(type).matches()) invalid(path + ".factType", "必须是最长 32 位的大写枚举标识");
        bounded(text(fact, "subject", null), 100, path + ".subject", false);
        bounded(text(fact, "predicate", null), 100, path + ".predicate", false);
        enumValue(text(fact, "visibility", "UNKNOWN"), VISIBILITIES, path + ".visibility");
        enumValue(text(fact, "status", "ACTIVE"), FACT_STATUSES, path + ".status");
        if (!fact.has("value") && !fact.has("factValue")) invalid(path + ".value", "不能为空");
        bounded(valueText(fact, fact.has("value") ? "value" : "factValue", ""), 20_000,
                path + ".value", false);
    }

    private void validateCharacterState(JsonNode change, String path) {
        object(change, path);
        bounded(text(change, "character", text(change, "characterName", null)), 100,
                path + ".character", true);
        bounded(text(change, "field", text(change, "stateKey", "state")), 100,
                path + ".field", true);
        String valueField = change.has("newValue") ? "newValue" : change.has("state") ? "state"
                : change.has("value") ? "value" : null;
        if (valueField == null) invalid(path + ".newValue", "不能为空");
        bounded(valueText(change, valueField, ""), 20_000, path + ".newValue", true);
        enumValue(text(change, "visibility", "PUBLIC"), VISIBILITIES, path + ".visibility");
    }

    private void validateRelationship(JsonNode relation, String path) {
        object(relation, path);
        bounded(text(relation, "relationshipKey", null), 220, path + ".relationshipKey", false);
        bounded(text(relation, "characterA", null), 100, path + ".characterA", true);
        bounded(text(relation, "characterB", null), 100, path + ".characterB", true);
        bounded(text(relation, "relation", null), 100, path + ".relation", false);
        bounded(text(relation, "publicStatus", null), 255, path + ".publicStatus", false);
        bounded(text(relation, "privateStatus", null), 255, path + ".privateStatus", false);
        score(relation, "trust", path); score(relation, "conflict", path);
    }

    private void validateThread(JsonNode thread, String path) {
        object(thread, path);
        bounded(text(thread, "threadKey", null), 128, path + ".threadKey", true);
        bounded(text(thread, "description", null), 20_000, path + ".description", true);
        enumValue(text(thread, "status", "OPEN"), THREAD_STATUSES, path + ".status");
        JsonNode clues = thread.get("knownClues");
        if (clues != null && !clues.isNull()) {
            if (!clues.isArray() || clues.size() > 30) invalid(path + ".knownClues", "必须是最多 30 项的数组");
            for (int i=0;i<clues.size();i++) bounded(clues.get(i).asText(),20_000,
                    path + ".knownClues[" + i + "]", true);
        }
    }

    private void validateForeshadow(JsonNode item, String path) {
        object(item, path);
        bounded(text(item, "foreshadowKey", null), 128, path + ".foreshadowKey", true);
        bounded(text(item, "setup", null), 20_000, path + ".setup", true);
        bounded(text(item, "payoffPlan", null), 20_000, path + ".payoffPlan", false);
        enumValue(text(item, "status", "SETUP"), FORESHADOW_STATUSES, path + ".status");
    }

    private void validateArray(JsonNode root, String field, int max, ItemValidator validator) {
        JsonNode values = root.get(field); if (values == null || values.isNull()) return;
        if (!values.isArray() || values.size() > max) invalid("memoryUpdate." + field, "必须是最多 " + max + " 项的数组");
        for (int i=0;i<values.size();i++) validator.validate(values.get(i), "memoryUpdate." + field + "[" + i + "]");
    }

    private void validateKeyArray(JsonNode root, String field, int max, String objectKey) {
        validateArray(root, field, max, (value, path) -> {
            String key = value.isTextual() ? value.asText() : text(value, objectKey, null);
            bounded(key, 128, path, true);
        });
    }

    private void object(JsonNode value, String path) { if (!value.isObject()) invalid(path, "必须是对象"); }
    private void bounded(String value, int max, String path, boolean required) {
        if (value == null || value.trim().isEmpty()) { if (required) invalid(path, "不能为空"); return; }
        if (value.length() > max) invalid(path, "长度不能超过 " + max);
    }
    private void enumValue(String value, Set<String> allowed, String path) {
        if (!allowed.contains(upper(value))) invalid(path, "必须是 " + allowed + " 之一");
    }
    private void score(JsonNode node, String field, String path) {
        JsonNode value=node.get(field); if(value==null||value.isNull())return;
        if(!value.canConvertToInt()||value.asInt()<0||value.asInt()>100) invalid(path+"."+field,"必须在 0 到 100 之间");
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private void invalid(String path, String reason) { throw new IllegalArgumentException(path + " " + reason); }
    @FunctionalInterface private interface ItemValidator { void validate(JsonNode value, String path); }

    private void persistSummary(StoryChapter chapter, StoryChapterVersion version, JsonNode raw) {
        JsonNode summary = raw == null ? support.mapper().createObjectNode() : raw;
        String text = summary.isTextual() ? summary.asText() : text(summary, "summary", "");
        if (text.isBlank()) text = "第" + chapter.getChapterNo() + "章已批准";
        if (jdbc.queryForObject("SELECT COUNT(*) FROM story_chapter_summary WHERE chapter_version_id=?",
                Integer.class, version.getId()) > 0) return;
        jdbc.update("""
                INSERT INTO story_chapter_summary
                  (chapter_id, chapter_version_id, summary, main_events_json, character_changes_json,
                   opened_threads_json, resolved_threads_json, ending_hook, created_time)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, chapter.getId(), version.getId(), text,
                json(summary.get("mainEvents")), json(summary.get("characterChanges")),
                json(summary.get("openedThreads")), json(summary.get("resolvedThreads")),
                text(summary, "endingHook", null), LocalDateTime.now());
    }

    private void upsertFact(StoryChapter chapter, JsonNode fact) {
        String key = text(fact, "factKey", null);
        if (key == null) key = support.sha256(text(fact,"subject","") + ":" + text(fact,"predicate","")).substring(0, 24);
        String value = valueText(fact, "value", valueText(fact, "factValue", ""));
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM story_fact WHERE story_id=? AND fact_key=?",
                Integer.class, chapter.getStoryId(), key);
        if (count != null && count > 0) {
            // Locked canon is immutable. A conflicting model proposal remains visible in the task result warning.
            jdbc.update("""
                    UPDATE story_fact SET fact_type=?, subject_name=?, predicate_name=?, fact_value=?,
                      visibility=?, source_chapter_no=?, status=?, updated_time=?
                    WHERE story_id=? AND fact_key=? AND locked=FALSE
                    """, upper(text(fact,"factType","OTHER")), text(fact,"subject",null),
                    text(fact,"predicate",null), value, upper(text(fact,"visibility","UNKNOWN")),
                    chapter.getChapterNo(), upper(text(fact,"status","ACTIVE")), LocalDateTime.now(),
                    chapter.getStoryId(), key);
        } else {
            jdbc.update("""
                    INSERT INTO story_fact
                      (story_id,fact_key,fact_type,subject_name,predicate_name,fact_value,visibility,locked,
                       source_chapter_no,status,created_time,updated_time)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, chapter.getStoryId(), key, upper(text(fact,"factType","OTHER")), text(fact,"subject",null),
                    text(fact,"predicate",null), value, upper(text(fact,"visibility","UNKNOWN")),
                    fact.path("locked").asBoolean(false), chapter.getChapterNo(), upper(text(fact,"status","ACTIVE")),
                    LocalDateTime.now(), LocalDateTime.now());
        }
    }
    private void upsertCharacterState(StoryChapter chapter, JsonNode change) {
        String character = text(change,"character",text(change,"characterName","unknown"));
        String field = text(change,"field",text(change,"stateKey","state"));
        var fact = support.mapper().createObjectNode(); fact.put("factKey", characterStateKey(character, field));
        fact.put("factType", "CHARACTER_STATE"); fact.put("subject", character); fact.put("predicate", field);
        JsonNode state = change.has("newValue") ? change.get("newValue")
                : change.has("state") ? change.get("state") : change.path("value");
        fact.set("value", state);
        fact.put("visibility", upper(text(change,"visibility","PUBLIC"))); upsertFact(chapter, fact);
    }
    String characterStateKey(String character, String field) {
        String readable = "character_state:" + character + ":" + field;
        return readable.length() <= 128 ? readable
                : "character_state:" + support.sha256(character + ":" + field).substring(0, 64);
    }
    private void upsertRelationship(StoryChapter chapter, JsonNode relation) {
        String a=text(relation,"characterA",""); String b=text(relation,"characterB","");
        String key=text(relation,"relationshipKey", a.compareTo(b)<=0 ? a+"|"+b : b+"|"+a);
        int exists=jdbc.queryForObject("SELECT COUNT(*) FROM story_relationship WHERE story_id=? AND relationship_key=?",
                Integer.class,chapter.getStoryId(),key);
        Object[] values={a,b,text(relation,"relation",null),nullableInt(relation,"trust"),nullableInt(relation,"conflict"),
                text(relation,"publicStatus",null),text(relation,"privateStatus",null),chapter.getChapterNo(),support.write(relation),
                LocalDateTime.now(),chapter.getStoryId(),key};
        if(exists>0) jdbc.update("""
                UPDATE story_relationship SET character_a=?,character_b=?,relation_name=?,trust_score=?,conflict_score=?,
                 public_status=?,private_status=?,updated_at_chapter_no=?,state_json=?,updated_time=?
                WHERE story_id=? AND relationship_key=?
                """,values);
        else jdbc.update("""
                INSERT INTO story_relationship
                 (character_a,character_b,relation_name,trust_score,conflict_score,public_status,private_status,
                  updated_at_chapter_no,state_json,updated_time,story_id,relationship_key,created_time)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,a,b,text(relation,"relation",null),nullableInt(relation,"trust"),nullableInt(relation,"conflict"),
                text(relation,"publicStatus",null),text(relation,"privateStatus",null),chapter.getChapterNo(),
                support.write(relation),LocalDateTime.now(),chapter.getStoryId(),key,LocalDateTime.now());
    }
    private void upsertThread(StoryChapter chapter, JsonNode thread, String status) {
        status=upper(status);
        String key=text(thread,"threadKey",null); if(key==null)return;
        int exists=jdbc.queryForObject("SELECT COUNT(*) FROM story_plot_thread WHERE story_id=? AND thread_key=?",
                Integer.class,chapter.getStoryId(),key);
        if(exists>0) jdbc.update("""
                UPDATE story_plot_thread SET description=?,expected_payoff_chapter_no=?,
                  clues_json=?,
                  resolved_chapter_no=CASE WHEN status='RESOLVED' THEN resolved_chapter_no ELSE ? END,
                  status=CASE WHEN status='RESOLVED' THEN status ELSE ? END,
                  updated_time=? WHERE story_id=? AND thread_key=?
                """,text(thread,"description",key),nullableInt(thread,"expectedPayoffChapter"),
                json(thread.get("knownClues")),"RESOLVED".equals(status)?chapter.getChapterNo():null,status,
                LocalDateTime.now(),chapter.getStoryId(),key);
        else jdbc.update("""
                INSERT INTO story_plot_thread
                 (story_id,thread_key,description,introduced_chapter_no,expected_payoff_chapter_no,resolved_chapter_no,
                  status,clues_json,created_time,updated_time) VALUES (?,?,?,?,?,?,?,?,?,?)
                """,chapter.getStoryId(),key,text(thread,"description",key),chapter.getChapterNo(),
                nullableInt(thread,"expectedPayoffChapter"),"RESOLVED".equals(status)?chapter.getChapterNo():null,
                status,json(thread.get("knownClues")),LocalDateTime.now(),LocalDateTime.now());
    }
    private void resolveThread(StoryChapter chapter, JsonNode value) {
        String key=value.isTextual()?value.asText():text(value,"threadKey",null); if(key==null)return;
        jdbc.update("UPDATE story_plot_thread SET status='RESOLVED',resolved_chapter_no=?,updated_time=? WHERE story_id=? AND thread_key=?",
                chapter.getChapterNo(),LocalDateTime.now(),chapter.getStoryId(),key);
    }
    private void upsertForeshadow(StoryChapter chapter, JsonNode item) {
        String key=text(item,"foreshadowKey",null); if(key==null)return;
        int exists=jdbc.queryForObject("SELECT COUNT(*) FROM story_foreshadowing WHERE story_id=? AND foreshadow_key=?",
                Integer.class,chapter.getStoryId(),key);
        if(exists>0) jdbc.update("""
                UPDATE story_foreshadowing SET setup_text=?,payoff_plan=?,payoff_chapter_no=?,
                  status=CASE WHEN status='PAID_OFF' THEN status ELSE ? END,updated_time=?
                WHERE story_id=? AND foreshadow_key=?
                """,text(item,"setup",key),text(item,"payoffPlan",null),nullableInt(item,"payoffChapter"),
                upper(text(item,"status","SETUP")),LocalDateTime.now(),chapter.getStoryId(),key);
        else jdbc.update("""
                INSERT INTO story_foreshadowing
                 (story_id,foreshadow_key,setup_text,setup_chapter_no,payoff_plan,payoff_chapter_no,status,created_time,updated_time)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,chapter.getStoryId(),key,text(item,"setup",key),chapter.getChapterNo(),text(item,"payoffPlan",null),
                nullableInt(item,"payoffChapter"),upper(text(item,"status","SETUP")),LocalDateTime.now(),LocalDateTime.now());
    }
    private void payOffForeshadow(StoryChapter chapter, JsonNode value) {
        String key=value.isTextual()?value.asText():text(value,"foreshadowKey",null); if(key==null)return;
        jdbc.update("UPDATE story_foreshadowing SET status='PAID_OFF',actual_payoff_chapter_no=?,updated_time=? WHERE story_id=? AND foreshadow_key=?",
                chapter.getChapterNo(),LocalDateTime.now(),chapter.getStoryId(),key);
    }
    private ArrayNode array(JsonNode node,String field){JsonNode v=node.get(field);return v!=null&&v.isArray()?(ArrayNode)v:support.mapper().createArrayNode();}
    private String text(JsonNode n,String f,String d){JsonNode v=n==null?null:n.get(f);return v==null||v.isNull()?d:v.asText();}
    private String valueText(JsonNode n,String f,String d){JsonNode v=n.get(f);return v==null||v.isNull()?d:(v.isTextual()?v.asText():v.toString());}
    private Integer nullableInt(JsonNode n,String f){JsonNode v=n.get(f);return v!=null&&v.canConvertToInt()?v.asInt():null;}
    private String json(JsonNode n){return n==null||n.isNull()?null:support.write(n);}
}
