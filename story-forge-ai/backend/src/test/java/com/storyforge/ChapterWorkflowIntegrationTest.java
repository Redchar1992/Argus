package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.service.ChapterContextAssembler;
import com.storyforge.chapter.service.ChapterEventService;
import com.storyforge.chapter.stream.ChapterCommandPublisher;
import com.storyforge.common.exception.ApiException;
import com.storyforge.story.StoryProjectMapper;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@ActiveProfiles("local")
@AutoConfigureMockMvc
@SpringBootTest(properties={
        "spring.datasource.url=jdbc:h2:mem:storyforge-chapter;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.chapter-workflow.redis-enabled=false"
})
class ChapterWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChapterContextAssembler contextAssembler;
    @Autowired ChapterEventService eventService;
    @Autowired StoryProjectMapper stories;
    @Autowired AiTaskMapper tasks;
    @MockBean ChapterCommandPublisher publisher;
    private final List<Map<String,String>> published=new ArrayList<>();

    @BeforeEach void clean(){
        reset(publisher);published.clear();
        when(publisher.publish(anyMap())).thenAnswer(inv->{
            @SuppressWarnings("unchecked") Map<String,String> fields=inv.getArgument(0);
            published.add(new LinkedHashMap<>(fields));return "command-"+published.size();
        });
        jdbc.update("DELETE FROM ai_task_event");jdbc.update("DELETE FROM story_chapter_summary");
        jdbc.update("DELETE FROM story_rewrite_proposal");jdbc.update("UPDATE story_chapter SET current_version_id=NULL");
        jdbc.update("DELETE FROM story_chapter_version");jdbc.update("DELETE FROM story_fact");
        jdbc.update("DELETE FROM story_relationship");jdbc.update("DELETE FROM story_plot_thread");
        jdbc.update("DELETE FROM story_foreshadowing");jdbc.update("DELETE FROM story_artifact");
        jdbc.update("DELETE FROM ai_task");jdbc.update("DELETE FROM story_chapter");
        jdbc.update("DELETE FROM story_project");jdbc.update("DELETE FROM sys_user");
    }

    @Test void completeChapterFlowPersistsImmutableVersionsRewriteMemoryAndNextContext() throws Exception {
        JsonNode registration=body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"chapter-writer\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated()).andReturn());
        String token=registration.path("token").asText();long userId=registration.path("userId").asLong();
        long storyId=body(mvc.perform(post("/api/story/create").header(HttpHeaders.AUTHORIZATION,bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"秘密账单","genre":"都市情感","audience":"女性","keywords":"复仇"}
                        """)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        seedApprovedOutline(userId,storyId);

        StoryChapter chapterWithoutOutlinePair=new StoryChapter();chapterWithoutOutlinePair.setChapterNo(3);
        ApiException missingPair=org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                ()->contextAssembler.assemble(stories.selectById(storyId),chapterWithoutOutlinePair,1200));
        assertThat(missingPair.getCode()).isEqualTo("CHAPTER_OUTLINE_NODES_UNAVAILABLE");
        assertThat(missingPair.getMessage()).contains("索引 4 和 5","当前节点数 4");

        long planTask=body(mvc.perform(post("/api/stories/{storyId}/chapters/1/plan",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetLength\":1200}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.chapterId").isNumber()).andReturn())
                .path("taskId").asLong();
        Map<String,String> planCommand=published.get(0);
        assertThat(planCommand).containsKeys("taskId","storyId","chapterId","chapterNo","action","threadId","idempotencyKey","payload");
        assertThat(planCommand.get("action")).isEqualTo("PLAN");
        JsonNode planPayload=mapper.readTree(planCommand.get("payload"));
        assertThat(planPayload.has("targetAudience")).isTrue();
        assertThat(planPayload.path("characters").get(0).path("name").asText()).isEqualTo("林晚");
        assertThat(planPayload.path("outlineNodes").size()).isEqualTo(2);
        assertThat(planPayload.path("outlineNodes").findValuesAsText("event"))
                .containsExactly("发现账单","公开对峙");
        assertThat(planPayload.path("currentOutlineNodes")).isEqualTo(planPayload.path("outlineNodes"));
        assertThat(planPayload.fieldNames()).toIterable().doesNotContain("TARGETAUDIENCE","OUTLINENODES");
        // A missing progress event must not block the later authoritative
        // terminal event (Redis delivery remains ordered by stream ID).
        eventService.process("1000-0",event(planTask,"CHAPTER_PLAN_READY",2,"SUCCESS",planData()));

        JsonNode chapter=body(mvc.perform(get("/api/stories/{storyId}/chapters/1/plan",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PLAN_READY"))
                .andExpect(jsonPath("$.plan.scenes.length()").value(3)).andReturn());
        long chapterId=chapter.path("id").asLong();String planHash=chapter.path("planHash").asText();
        mvc.perform(post("/api/stories/{storyId}/chapters/1/plan/approve",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planHash\":\""+planHash+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PLAN_APPROVED"));

        long generateTask=body(mvc.perform(post("/api/stories/{storyId}/chapters/1/generate",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)))
                .andExpect(status().isAccepted()).andReturn()).path("taskId").asLong();
        assertThat(mapper.readTree(published.get(1).get("payload")).path("chapterPlan").path("chapterTitle").asText())
                .isEqualTo("账单背后");
        eventService.process("2000-0",event(generateTask,"TASK_STARTED",1,"RUNNING","{}"));
        eventService.process("2001-0",event(generateTask,"GENERATION_STARTED",2,"RUNNING","{}"));
        eventService.process("2002-0",event(generateTask,"TOKEN_DELTA",3,"RUNNING","{\"text\":\"林晚\"}"));
        String draft="林晚推开会议室的门。\n\n门外有人拦住她。\n\n她举起账单。\n\n丈夫脸色骤变。\n\n手机响起陌生来电。";
        eventService.process("2003-0",event(generateTask,"DRAFT_READY",4,"RUNNING",
                mapper.createObjectNode().put("content",draft).put("revisionCount",0).toString()));
        assertThat(count("story_chapter_version","chapter_id",chapterId)).isEqualTo(1);
        String revised=draft.replace("有人拦住她","陈宇伸手拦住她，试图夺走账单");
        eventService.process("2004-0",event(generateTask,"REVISION_READY",5,"RUNNING",
                mapper.createObjectNode().put("content",revised).put("revisionCount",1).toString()));
        assertThat(count("story_chapter_version","chapter_id",chapterId)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT content_hash FROM story_chapter_version WHERE chapter_id=? ORDER BY version_no",String.class,chapterId))
                .containsExactly(sha(draft),sha(revised));
        long revisionVersion=jdbc.queryForObject("SELECT current_version_id FROM story_chapter WHERE id=?",Long.class,chapterId);
        JsonNode review=mapper.readTree("{\"totalScore\":86,\"fatalProblems\":[]}");
        var reviewData=mapper.createObjectNode().put("content",revised).put("revisionCount",1);
        reviewData.set("review",review);reviewData.set("mechanicalErrors",mapper.createArrayNode());
        eventService.process("2005-0",event(generateTask,"HUMAN_REVIEW_REQUIRED",6,"REVIEW_REQUIRED",reviewData.toString()));
        assertThat(count("story_chapter_version","chapter_id",chapterId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM story_chapter WHERE id=?",Long.class,chapterId))
                .isEqualTo(revisionVersion);
        // A terminal marker replay uses a new Redis ID and sequence but must not duplicate a version.
        int beforeReplay=count("story_chapter_version","chapter_id",chapterId);
        eventService.process("2006-0",event(generateTask,"HUMAN_REVIEW_REQUIRED",7,"REVIEW_REQUIRED",reviewData.toString()));
        assertThat(count("story_chapter_version","chapter_id",chapterId)).isEqualTo(beforeReplay);
        assertThat(jdbc.queryForObject("SELECT review_json FROM story_chapter_version WHERE id=(SELECT current_version_id FROM story_chapter WHERE id=?)",String.class,chapterId)).contains("totalScore");

        JsonNode afterGeneration=body(mvc.perform(get("/api/chapters/{chapterId}",chapterId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW_REQUIRED"))
                .andExpect(jsonPath("$.activeTaskId").value(generateTask))
                .andExpect(jsonPath("$.activeTaskStatus").value("REVIEW_REQUIRED")).andReturn());
        long reviewedVersion=afterGeneration.path("currentVersionId").asLong();
        String edited=revised+"\n\n她决定追查到底。";
        JsonNode editedVersion=body(mvc.perform(put("/api/chapters/{chapterId}/content",chapterId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("baseVersionId",reviewedVersion).put("content",edited).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceType").value("USER_EDIT")).andReturn());
        long editVersionId=editedVersion.path("id").asLong();

        String selected="陈宇伸手拦住她";int start=edited.indexOf(selected);int end=start+selected.length();
        var rewriteRequest=mapper.createObjectNode().put("chapterVersionId",editVersionId).put("startOffset",start)
                .put("endOffset",end).put("selectedText",selected).put("selectedTextHash",sha(selected))
                .put("action","ENHANCE_CONFLICT").put("customInstruction","");
        long rewriteTask=body(mvc.perform(post("/api/chapters/{chapterId}/rewrite-selection",chapterId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(rewriteRequest.toString())).andExpect(status().isAccepted()).andReturn()).path("taskId").asLong();
        var proposalData=mapper.createObjectNode().put("chapterVersionId",editVersionId).put("originalText",selected)
                .put("replacementText","陈宇猛地扣住她的手腕，另一只手直扑账单")
                .put("reason","增强了可见阻力").put("selectedTextHash",sha(selected));
        var processed=eventService.process("3000-0",event(rewriteTask,"REWRITE_PROPOSAL_READY",1,"SUCCESS",proposalData.toString()));
        long proposalId=processed.event().data().path("proposalId").asLong();
        assertThat(proposalId).isPositive();
        JsonNode accepted=body(mvc.perform(post("/api/chapters/{chapterId}/rewrite-proposals/{proposalId}/accept",chapterId,proposalId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseVersionId\":"+editVersionId+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceType").value("AI_SELECTION_REWRITE")).andReturn());
        assertThat(accepted.path("content").asText()).contains("猛地扣住").doesNotContain(selected);
        long acceptedVersion=accepted.path("id").asLong();

        mvc.perform(get("/api/chapters/{chapterId}/versions/compare",chapterId)
                .param("fromVersionId",String.valueOf(editVersionId)).param("toVersionId",String.valueOf(acceptedVersion))
                .header(HttpHeaders.AUTHORIZATION,bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.fromChangedText").value("伸手拦住她"))
                .andExpect(jsonPath("$.toChangedText").value("猛地扣住她的手腕，另一只手直扑账单"));
        JsonNode restored=body(mvc.perform(post("/api/chapters/{chapterId}/versions/{versionId}/restore",chapterId,editVersionId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceType").value("RESTORE")).andReturn());
        long restoredVersion=restored.path("id").asLong();

        long finalizeTask=body(mvc.perform(post("/api/chapters/{chapterId}/approve",chapterId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn()).path("taskId").asLong();
        AiTask generatedTask=tasks.selectById(generateTask);AiTask finalTask=tasks.selectById(finalizeTask);
        assertThat(finalTask.getThreadId()).isEqualTo(generatedTask.getThreadId());
        assertThat(finalTask.getParentTaskId()).isEqualTo(generateTask);
        jdbc.update("""
                INSERT INTO story_fact
                 (story_id,fact_key,fact_type,fact_value,visibility,locked,status,created_time,updated_time)
                VALUES (?,'identity_lin_wan','IDENTITY','林氏继承人','READER_ONLY',TRUE,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,storyId);
        var finalData=mapper.createObjectNode().put("content",restored.path("content").asText());
        finalData.set("review",review);finalData.set("summary",mapper.readTree("""
                {"chapterNo":1,"summary":"林晚在会议室公开账单并决定继续调查。","mainEvents":["公开账单"],
                 "characterChanges":["林晚不再退让"],"openedThreads":[{"threadKey":"account_owner"}],
                 "resolvedThreads":[],"endingHook":"陌生来电说出父亲名字"}
                """));
        finalData.set("memoryUpdate",mapper.readTree("""
                {"newFacts":[{"factKey":"arm_injury","factType":"STATE","subject":"林晚","predicate":"手臂","value":"受伤","visibility":"PUBLIC","locked":false},
                             {"factKey":"father_dead","factType":"CHARACTER_STATE","subject":"林父","predicate":"生存状态","value":"已死亡","visibility":"PUBLIC","locked":true},
                             {"factKey":"evidence_owner","factType":"ITEM_STATE","subject":"证据文件","predicate":"当前持有人","value":"陈宇","visibility":"PUBLIC","locked":false},
                             {"factKey":"identity_lin_wan","factType":"IDENTITY","value":"错误覆盖值"}],
                 "changedRelationships":[{"characterA":"林晚","characterB":"陈宇","relation":"夫妻","trust":10,"conflict":85}],
                 "openedThreads":[{"threadKey":"account_owner","description":"秘密账户收款人身份","knownClues":["账单"]}],
                 "updatedThreads":[],"resolvedThreads":[],
                 "newForeshadowing":[{"foreshadowKey":"red_folder","setup":"红色文件夹被藏起","payoffPlan":"股份协议"}],
                 "paidOffForeshadowing":[],"characterStateChanges":[],"continuityWarnings":[]}
                """));
        eventService.process("4000-0",event(finalizeTask,"FINAL_READY",1,"SUCCESS",finalData.toString()));
        int approvedVersions=count("story_chapter_version","chapter_id",chapterId);
        // Completed command redelivery publishes a new sequence; finalization and memory remain idempotent.
        eventService.process("4001-0",event(finalizeTask,"FINAL_READY",2,"SUCCESS",finalData.toString()));
        assertThat(count("story_chapter_version","chapter_id",chapterId)).isEqualTo(approvedVersions);
        assertThat(jdbc.queryForObject("SELECT status FROM story_chapter WHERE id=?",String.class,chapterId)).isEqualTo("APPROVED");
        assertThat(count("story_chapter_summary","chapter_id",chapterId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT fact_value FROM story_fact WHERE story_id=? AND fact_key='arm_injury'",String.class,storyId)).isEqualTo("受伤");
        assertThat(jdbc.queryForObject("SELECT fact_value FROM story_fact WHERE story_id=? AND fact_key='identity_lin_wan'",String.class,storyId)).isEqualTo("林氏继承人");

        mvc.perform(get("/api/ai-tasks/{taskId}/events/history",finalizeTask)
                .header(HttpHeaders.AUTHORIZATION,bearer(token))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("FINAL_READY"));
        MvcResult sse=mvc.perform(get("/api/ai-tasks/{taskId}/events",finalizeTask)
                        .header(HttpHeaders.AUTHORIZATION,bearer(token))
                        .header("Last-Event-ID","4000-0"))
                .andExpect(request().asyncStarted()).andReturn();
        sse.getAsyncResult(1000);
        mvc.perform(asyncDispatch(sse)).andExpect(status().isOk())
                .andExpect(result->assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("id:4001-0","event:FINAL_READY","approvedVersionId"));
        mvc.perform(get("/api/ai-tasks/{taskId}",finalizeTask).header(HttpHeaders.AUTHORIZATION,bearer(token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskType").value("CHAPTER_FINALIZE"))
                .andExpect(jsonPath("$.chapterId").value(chapterId)).andExpect(jsonPath("$.result.approvedVersionId").isNumber());

        mvc.perform(post("/api/stories/{storyId}/chapters/2/plan",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        JsonNode nextPayload=mapper.readTree(published.get(published.size()-1).get("payload"));
        assertThat(nextPayload.path("outlineNodes").findValuesAsText("event"))
                .containsExactly("追查账户","发现父亲线索");
        assertThat(nextPayload.path("currentOutlineNodes")).isEqualTo(nextPayload.path("outlineNodes"));
        assertThat(nextPayload.path("recentSummaries").get(0).path("chapterNo").asInt()).isEqualTo(1);
        assertThat(nextPayload.path("canonFacts"))
                .anySatisfy(fact->assertThat(fact.path("factKey").asText()).isEqualTo("arm_injury"));
        assertThat(nextPayload.path("canonFacts"))
                .anySatisfy(fact->{assertThat(fact.path("factKey").asText()).isEqualTo("identity_lin_wan");
                    assertThat(fact.path("visibility").asText()).isEqualTo("READER_ONLY");});
        assertThat(nextPayload.path("canonFacts"))
                .anySatisfy(fact->{assertThat(fact.path("factKey").asText()).isEqualTo("father_dead");
                    assertThat(fact.path("value").asText()).isEqualTo("已死亡");});
        assertThat(nextPayload.path("canonFacts"))
                .anySatisfy(fact->{assertThat(fact.path("factKey").asText()).isEqualTo("evidence_owner");
                    assertThat(fact.path("value").asText()).isEqualTo("陈宇");});
        assertThat(nextPayload.path("relationshipStates").get(0).path("characterA").asText()).isEqualTo("林晚");
        assertThat(nextPayload.path("unresolvedThreads").get(0).path("threadKey").asText()).isEqualTo("account_owner");
    }

    @Test void staleHashOwnershipAndUnknownCharactersAreRejectedWithoutMutation() throws Exception {
        JsonNode owner=register("chapter-owner");JsonNode other=register("chapter-other");
        String token=owner.path("token").asText();long storyId=createStory(token);seedApprovedOutline(owner.path("userId").asLong(),storyId);
        long planTask=body(mvc.perform(post("/api/stories/{storyId}/chapters/1/plan",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn()).path("taskId").asLong();
        Map<String,Object> beforeWrongPlan=jdbc.queryForMap("""
                SELECT c.status AS chapter_status,c.plan_status,c.plan_json,t.status AS task_status,t.result_payload
                FROM story_chapter c JOIN ai_task t ON t.chapter_id=c.id WHERE t.id=?
                """,planTask);
        String wrongChapterPlan=planData()
                .replace("发现账单","参加错误章节的董事会")
                .replace("保存秘密账单","争取错误章节的董事席位");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                ()->eventService.process("4999-0",event(planTask,"CHAPTER_PLAN_READY",1,"SUCCESS",wrongChapterPlan)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task_event WHERE task_id=?",Integer.class,planTask)).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT c.status AS chapter_status,c.plan_status,c.plan_json,t.status AS task_status,t.result_payload
                FROM story_chapter c JOIN ai_task t ON t.chapter_id=c.id WHERE t.id=?
                """,planTask)).isEqualTo(beforeWrongPlan);
        String badPlan=planData().replace("林晚","陌生人");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                ()->eventService.process("5000-0",event(planTask,"CHAPTER_PLAN_READY",1,"SUCCESS",badPlan)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task_event WHERE task_id=?",Integer.class,planTask)).isZero();
        mvc.perform(get("/api/ai-tasks/{taskId}/events/history",planTask)
                .header(HttpHeaders.AUTHORIZATION,bearer(other.path("token").asText())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AI_TASK_FORBIDDEN"));
    }

    @Test void deterministicInvalidEventBecomesDurableFailureInsteadOfPoisonRetry() throws Exception {
        JsonNode owner=register("invalid-event-owner");String token=owner.path("token").asText();
        long storyId=createStory(token);seedApprovedOutline(owner.path("userId").asLong(),storyId);
        long planTask=body(mvc.perform(post("/api/stories/{storyId}/chapters/1/plan",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn()).path("taskId").asLong();
        Map<String,String> fields=event(planTask,"CHAPTER_PLAN_READY",1,"SUCCESS",
                planData().replace("林晚","未登记角色"));
        IllegalArgumentException invalid=org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                ()->eventService.process("poison-1",fields));

        var rejected=eventService.rejectInvalidEvent("poison-1",fields,invalid);

        assertThat(rejected.persisted()).isTrue();
        assertThat(rejected.event().type()).isEqualTo("TASK_FAILED");
        assertThat(rejected.event().errorCode()).isEqualTo("INVALID_CHAPTER_EVENT");
        assertThat(jdbc.queryForObject("SELECT status FROM ai_task WHERE id=?",String.class,planTask)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM story_chapter WHERE id=(SELECT chapter_id FROM ai_task WHERE id=?)",
                String.class,planTask)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_task_event WHERE task_id=?",Integer.class,planTask)).isOne();
    }

    @Test void ssePreflightAllowsReconnectHeaders() throws Exception {
        mvc.perform(options("/api/ai-tasks/1/events")
                        .header(HttpHeaders.ORIGIN,"http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,"GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "authorization,cache-control,last-event-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,"http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsStringIgnoringCase("last-event-id")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsStringIgnoringCase("cache-control")));
    }

    @Test void lateAiReviewNeverOverwritesUserVersionCreatedDuringStreaming() throws Exception {
        JsonNode owner=register("stream-editor");String token=owner.path("token").asText();
        long storyId=createStory(token);seedApprovedOutline(owner.path("userId").asLong(),storyId);
        long planTask=body(mvc.perform(post("/api/stories/{storyId}/chapters/1/plan",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn()).path("taskId").asLong();
        eventService.process("6000-0",event(planTask,"CHAPTER_PLAN_READY",1,"SUCCESS",planData()));
        mvc.perform(post("/api/stories/{storyId}/chapters/1/plan/approve",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token))).andExpect(status().isOk());
        long generationTask=body(mvc.perform(post("/api/stories/{storyId}/chapters/1/generate",storyId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token))).andExpect(status().isAccepted()).andReturn())
                .path("taskId").asLong();
        String aiDraft="第一段。\\n\\n第二段。\\n\\n第三段。\\n\\n第四段。\\n\\n第五段。";
        eventService.process("6001-0",event(generationTask,"DRAFT_READY",1,"RUNNING",
                mapper.createObjectNode().put("content",aiDraft).toString()));
        long chapterId=jdbc.queryForObject("SELECT chapter_id FROM ai_task WHERE id=?",Long.class,generationTask);
        long draftVersion=jdbc.queryForObject("SELECT current_version_id FROM story_chapter WHERE id=?",Long.class,chapterId);
        String userContent=aiDraft+"\\n\\n这是用户在流式期间新增的关键句。";
        long userVersion=body(mvc.perform(put("/api/chapters/{chapterId}/content",chapterId)
                .header(HttpHeaders.AUTHORIZATION,bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("baseVersionId",draftVersion).put("content",userContent).toString()))
                .andExpect(status().isOk()).andReturn()).path("id").asLong();
        var stale=mapper.createObjectNode().put("content",aiDraft+" AI晚到的修改");
        stale.set("review",mapper.readTree("{\"totalScore\":88}"));
        var result=eventService.process("6002-0",event(generationTask,"HUMAN_REVIEW_REQUIRED",2,
                "REVIEW_REQUIRED",stale.toString()));
        assertThat(result.event().data().path("staleResultDiscarded").asBoolean()).isTrue();
        assertThat(result.event().data().path("preservedVersionId").asLong()).isEqualTo(userVersion);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM story_chapter WHERE id=?",Long.class,chapterId))
                .isEqualTo(userVersion);
        assertThat(jdbc.queryForObject("SELECT content FROM story_chapter_version WHERE id=?",String.class,userVersion))
                .isEqualTo(userContent);
    }

    private void seedApprovedOutline(long userId,long storyId){
        jdbc.update("UPDATE story_project SET status='WORKFLOW_COMPLETED' WHERE id=?",storyId);
        jdbc.update("""
                INSERT INTO ai_task(user_id,story_id,task_type,status,request_payload,created_time,updated_time)
                VALUES (?,?,'STORY_WORKFLOW','SUCCESS','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,userId,storyId);
        long taskId=jdbc.queryForObject("SELECT MAX(id) FROM ai_task",Long.class);
        String content="""
                {"characters":[{"name":"林晚","role":"女主"},{"name":"陈宇","role":"丈夫"}],
                 "outline":[
                    {"node_no":1,"event":"发现账单","protagonist_goal":"保存秘密账单"},
                    {"node_no":2,"event":"公开对峙","protagonist_goal":"迫使对方回应"},
                    {"node_no":3,"event":"追查账户","protagonist_goal":"确认账户归属"},
                    {"node_no":4,"event":"发现父亲线索","protagonist_goal":"查明父亲关联"}],
                 "score":{"total":88}}
                """;
        jdbc.update("""
                INSERT INTO story_artifact(story_id,task_id,artifact_type,version_no,status,content_json,created_time,updated_time)
                VALUES (?,?,'WORKFLOW_FINAL',1,'APPROVED',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """,storyId,taskId,content);
    }
    private String planData(){return """
            {"plan":{"chapterTitle":"账单背后","chapterGoal":"完成发现账单与保存秘密账单，完成公开对峙与迫使对方回应","openingHook":"会议门突然打开",
            "endingHook":"陌生电话响起","targetLength":1200,"scenes":[
            {"sceneNo":1,"location":"会议室","time":"白天","characters":["林晚"],"protagonistGoal":"保存秘密账单",
             "opposingForce":"门被锁住","visibleConflict":"她发现账单后拍门要求进入","informationRevealed":"账单存在",
             "emotionalChange":"从犹豫到坚定","setupOrPayoff":"账单伏笔","exitHook":"门突然打开","sceneFunction":"建立"},
            {"sceneNo":2,"location":"会议室","time":"白天","characters":["林晚","陈宇"],"protagonistGoal":"迫使对方回应",
             "opposingForce":"陈宇阻止","visibleConflict":"公开对峙中双方争夺账单","informationRevealed":"账户异常",
             "emotionalChange":"信任崩塌","setupOrPayoff":"账户线索","exitHook":"董事们沉默","sceneFunction":"升级"},
            {"sceneNo":3,"location":"走廊","time":"傍晚","characters":["林晚"],"protagonistGoal":"继续调查",
             "opposingForce":"证据被删","visibleConflict":"她追查删除者","informationRevealed":"父亲名字出现",
             "emotionalChange":"震惊后冷静","setupOrPayoff":"父亲伏笔","exitHook":"陌生电话响起","sceneFunction":"反转"}]}}
            """;}
    private Map<String,String> event(long taskId,String type,long sequence,String status,String data){
        AiTask task=tasks.selectById(taskId);long chapterNo=jdbc.queryForObject("SELECT chapter_no FROM story_chapter WHERE id=?",Integer.class,task.getChapterId());
        Map<String,String> f=new LinkedHashMap<>();f.put("taskId",String.valueOf(taskId));f.put("storyId",String.valueOf(task.getStoryId()));
        f.put("chapterId",String.valueOf(task.getChapterId()));f.put("chapterNo",String.valueOf(chapterNo));f.put("threadId",task.getThreadId());
        f.put("type",type);f.put("sequence",String.valueOf(sequence));f.put("status",status);f.put("currentNode",type.toLowerCase());
        f.put("progress","SUCCESS".equals(status)?"100":"50");f.put("idempotencyKey",task.getIdempotencyKey());f.put("data",data);
        f.put("errorCode","");f.put("errorMessage","");return f;
    }
    private JsonNode register(String username)throws Exception{return body(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\""+username+"\",\"password\":\"password123\"}"))
            .andExpect(status().isCreated()).andReturn());}
    private long createStory(String token)throws Exception{return body(mvc.perform(post("/api/story/create").header(HttpHeaders.AUTHORIZATION,bearer(token))
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"测试故事\",\"genre\":\"都市情感\"}"))
            .andExpect(status().isCreated()).andReturn()).path("id").asLong();}
    private int count(String table,String column,long value){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Integer.class,value);}
    private JsonNode body(MvcResult result)throws Exception{return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));}
    private String bearer(String token){return "Bearer "+token;}
    private String sha(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
}
