package com.storyforge.chapter.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.analytics.ProductAnalyticsService;
import com.storyforge.analytics.ProductEventNames;
import com.storyforge.chapter.ChapterStatus;
import com.storyforge.chapter.ChapterTaskType;
import com.storyforge.chapter.entity.AiTaskEvent;
import com.storyforge.chapter.entity.RewriteProposal;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import com.storyforge.chapter.mapper.AiTaskEventMapper;
import com.storyforge.chapter.mapper.RewriteProposalMapper;
import com.storyforge.chapter.mapper.StoryChapterMapper;
import com.storyforge.chapter.mapper.StoryChapterVersionMapper;
import com.storyforge.chapter.vo.TaskEventResponse;
import com.storyforge.common.config.ChapterWorkflowProperties;
import com.storyforge.cost.AiUsageRecorder;
import com.storyforge.cost.AiCreditService;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import com.storyforge.task.AiTaskStatus;
import com.storyforge.story.StoryProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ChapterEventService {
    private final AiTaskMapper tasks;
    private final StoryProjectMapper stories;
    private final StoryChapterMapper chapters;
    private final StoryChapterVersionMapper versions;
    private final RewriteProposalMapper proposals;
    private final AiTaskEventMapper events;
    private final ChapterVersionService versionService;
    private final StoryMemoryService memoryService;
    private final ChapterTaskService taskService;
    private final ChapterSupport support;
    private final ChapterWorkflowProperties properties;
    private final AiUsageRecorder usage;
    private final AiCreditService credits;
    private final ProductAnalyticsService analytics;

    public ChapterEventService(AiTaskMapper tasks, StoryProjectMapper stories,
            StoryChapterMapper chapters,
            StoryChapterVersionMapper versions, RewriteProposalMapper proposals, AiTaskEventMapper events,
            ChapterVersionService versionService, StoryMemoryService memoryService,
            ChapterTaskService taskService, ChapterSupport support, ChapterWorkflowProperties properties,
            AiUsageRecorder usage, AiCreditService credits, ProductAnalyticsService analytics) {
        this.tasks=tasks; this.stories=stories; this.chapters=chapters; this.versions=versions; this.proposals=proposals; this.events=events;
        this.versionService=versionService; this.memoryService=memoryService; this.taskService=taskService;
        this.support=support; this.properties=properties; this.usage=usage; this.credits=credits;
        this.analytics=analytics;
    }

    @Transactional
    public ProcessedEvent process(String redisEventId, Map<String,String> fields) {
        AiTaskEvent duplicate = events.selectOne(Wrappers.<AiTaskEvent>lambdaQuery()
                .eq(AiTaskEvent::getRedisEventId, redisEventId));
        if (duplicate != null) return new ProcessedEvent(false, response(duplicate, null, null));
        Long taskId=requiredLong(fields,"taskId");
        AiTask task=tasks.selectByIdForUpdate(taskId);
        if(task==null) throw new IllegalArgumentException("事件引用了不存在的 taskId: "+taskId);
        if(!ChapterTaskType.isChapterTask(task.getTaskType())) throw new IllegalArgumentException("事件引用的不是章节任务");
        validateIdentity(task,fields);
        if(stories.selectByIdForUpdate(task.getStoryId())==null)
            throw new IllegalArgumentException("任务故事不存在");
        long sequence=requiredLong(fields,"sequence");
        if(sequence<1) throw new IllegalArgumentException("sequence 必须大于 0");
        AiTaskEvent sameSequence=events.selectByTaskAndSequence(taskId,sequence);
        if(sameSequence!=null) return new ProcessedEvent(false,response(sameSequence,task,null));
        long latest=events.selectMaxSequence(taskId);
        // Redis Stream IDs remain the transport cursor. Sequence numbers are
        // task-local ordering hints and may contain a gap after an ambiguous
        // producer retry or acknowledged stream trimming. Never let a missing
        // non-terminal progress event permanently block later task state.
        if(sequence<=latest) return new ProcessedEvent(false,null);
        String type=required(fields,"type").toUpperCase(Locale.ROOT);
        JsonNode parsed=support.readRequired(required(fields,"data"),"data");
        if(!parsed.isObject()) throw new IllegalArgumentException("data 必须是 JSON 对象");
        ObjectNode data=(ObjectNode)parsed.deepCopy();
        String status=normalizeStatus(fields.get("status"),type);
        if(!AiTaskStatus.canTransition(task.getStatus(),status)) {
            return new ProcessedEvent(false, null);
        }
        String currentNode=truncate(trim(fields.get("currentNode")),64);
        Integer progress=optionalInt(fields.get("progress"));
        StoryChapter chapter=chapters.selectByIdForUpdate(task.getChapterId());
        if(chapter==null || !chapter.getStoryId().equals(task.getStoryId())) throw new IllegalArgumentException("任务章节不存在");

        applySideEffects(task,chapter,type,data);
        task.setStatus(status); task.setCurrentNode(currentNode);
        if(progress!=null) task.setProgress(Math.max(0,Math.min(100,progress)));
        task.setThreadId(identityThread(task,fields)); task.setLastEventId(redisEventId);
        task.setResultPayload(support.write(data)); task.setErrorCode(trim(fields.get("errorCode")));
        task.setErrorMessage(truncate(trim(fields.get("errorMessage")),1000));
        applyModelUsage(task,data); task.setUpdatedTime(LocalDateTime.now()); tasks.updateById(task);

        AiTaskEvent event=new AiTaskEvent(); event.setTaskId(taskId); event.setRedisEventId(redisEventId);
        event.setEventType(type); event.setSequenceNo(sequence); event.setStatus(status);
        event.setCurrentNode(currentNode); event.setProgress(task.getProgress()); event.setDataJson(support.write(data));
        event.setCreatedTime(LocalDateTime.now()); events.insert(event); trimEvents(taskId);
        if (isTerminal(status)) {
            JsonNode modelCalls = data.get("modelCalls");
            usage.recordModelCalls(task, task.getTaskType(),
                    modelCalls == null ? null : support.write(modelCalls),
                    AiTaskStatus.SUCCESS.equals(status), task.getErrorCode());
            settleCredits(task, status);
        }
        recordApprovalMilestones(task, chapter, type, status);
        return new ProcessedEvent(true,response(event,task,chapter));
    }

    private void settleCredits(AiTask task, String status) {
        long cost = taskService.configuredCreditCost(task.getTaskType());
        if (!credits.hasLog(ChapterTaskService.freezeKey(task))) return;
        if (AiTaskStatus.FAILED.equals(status)) {
            credits.release(task.getUserId(), task.getId(), ChapterTaskService.freezeKey(task), cost,
                    "章节 AI 任务失败，释放预冻结额度");
            return;
        }
        credits.settleFrozen(task.getUserId(), task.getId(), ChapterTaskService.freezeKey(task),
                ChapterTaskService.settleKey(task), cost, cost, "章节 AI 任务完成");
    }

    private void recordApprovalMilestones(
            AiTask task,
            StoryChapter chapter,
            String eventType,
            String status
    ) {
        if (!"FINAL_READY".equals(eventType)
                || !AiTaskStatus.SUCCESS.equals(status)
                || !ChapterStatus.APPROVED.equals(chapter.getStatus())) {
            return;
        }
        analytics.record(
                ProductEventNames.CHAPTER_APPROVED,
                task.getUserId(),
                task.getStoryId(),
                task.getId(),
                "chapter:" + chapter.getId() + ":approved",
                Map.of("chapterNo", chapter.getChapterNo())
        );
        Long approvedCount = chapters.selectCount(
                Wrappers.<StoryChapter>lambdaQuery()
                        .eq(StoryChapter::getStoryId, task.getStoryId())
                        .eq(StoryChapter::getStatus, ChapterStatus.APPROVED)
        );
        if (approvedCount != null && approvedCount >= 3) {
            analytics.record(
                    ProductEventNames.THIRD_CHAPTER_APPROVED,
                    task.getUserId(),
                    task.getStoryId(),
                    task.getId(),
                    "story:" + task.getStoryId() + ":third-chapter-approved",
                    Map.of("approvedChapterCount", approvedCount)
            );
        }
    }

    private boolean isTerminal(String status) {
        return AiTaskStatus.SUCCESS.equals(status)
                || AiTaskStatus.FAILED.equals(status)
                || AiTaskStatus.REVIEW_REQUIRED.equals(status);
    }

    /**
     * Converts a deterministic cross-service contract violation into a durable
     * failure instead of reclaiming the same poison event forever. Identity
     * mismatches are deliberately not allowed to mutate a task.
     */
    @Transactional
    public ProcessedEvent rejectInvalidEvent(String redisEventId, Map<String,String> fields,
            IllegalArgumentException failure) {
        AiTaskEvent duplicate = events.selectOne(Wrappers.<AiTaskEvent>lambdaQuery()
                .eq(AiTaskEvent::getRedisEventId, redisEventId));
        if (duplicate != null) return new ProcessedEvent(false, response(duplicate, null, null));
        Long taskId;
        try { taskId=requiredLong(fields,"taskId"); }
        catch (IllegalArgumentException ignored) { return new ProcessedEvent(false,null); }
        AiTask task=tasks.selectByIdForUpdate(taskId);
        if(task==null||!ChapterTaskType.isChapterTask(task.getTaskType())) return new ProcessedEvent(false,null);
        try { validateIdentity(task,fields); }
        catch (IllegalArgumentException ignored) { return new ProcessedEvent(false,null); }
        if(AiTaskStatus.SUCCESS.equals(task.getStatus())) return new ProcessedEvent(false,null);
        long sequence;
        try { sequence=requiredLong(fields,"sequence"); }
        catch (IllegalArgumentException ignored) { sequence=events.selectMaxSequence(taskId)+1; }
        long latest=events.selectMaxSequence(taskId);
        if(sequence<=latest) return new ProcessedEvent(false,null);
        StoryChapter chapter=chapters.selectByIdForUpdate(task.getChapterId());
        if(chapter==null||!chapter.getStoryId().equals(task.getStoryId())) return new ProcessedEvent(false,null);

        String message=truncate(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage(),1000);
        ObjectNode data=support.mapper().createObjectNode();
        data.put("sourceEventId",redisEventId);data.put("reason",message);
        persistFailure(task,chapter);
        task.setStatus(AiTaskStatus.FAILED);task.setCurrentNode("backend_validation");
        task.setProgress(Math.max(0,task.getProgress()==null?0:task.getProgress()));
        task.setLastEventId(redisEventId);task.setResultPayload(support.write(data));
        task.setErrorCode("INVALID_CHAPTER_EVENT");task.setErrorMessage(message);
        task.setUpdatedTime(LocalDateTime.now());tasks.updateById(task);

        AiTaskEvent event=new AiTaskEvent();event.setTaskId(taskId);event.setRedisEventId(redisEventId);
        event.setEventType("TASK_FAILED");event.setSequenceNo(sequence);event.setStatus(AiTaskStatus.FAILED);
        event.setCurrentNode("backend_validation");event.setProgress(task.getProgress());
        event.setDataJson(support.write(data));event.setCreatedTime(LocalDateTime.now());
        events.insert(event);trimEvents(taskId);
        settleCredits(task, AiTaskStatus.FAILED);
        return new ProcessedEvent(true,response(event,task,chapter));
    }

    private void applySideEffects(AiTask task, StoryChapter chapter, String type, ObjectNode data) {
        switch(type) {
            case "CHAPTER_PLAN_READY" -> persistPlan(task,chapter,data);
            case "DRAFT_READY" -> persistGeneratedVersion(task,chapter,data,"AI_DRAFT",ChapterStatus.GENERATING,type);
            case "REVISION_READY" -> persistGeneratedVersion(task,chapter,data,"AI_REVISION",ChapterStatus.GENERATING,type);
            case "HUMAN_REVIEW_REQUIRED" -> {
                String source=data.path("revisionCount").asInt(0)>0?"AI_REVISION":"AI_DRAFT";
                persistGeneratedVersion(task,chapter,data,source,ChapterStatus.REVIEW_REQUIRED,type);
                chapter.setStatus(ChapterStatus.REVIEW_REQUIRED); chapter.setUpdatedTime(LocalDateTime.now()); chapters.updateById(chapter);
            }
            case "REWRITE_PROPOSAL_READY" -> persistProposal(task,chapter,data);
            case "FINAL_READY" -> persistFinal(task,chapter,data);
            case "TASK_FAILED" -> persistFailure(task,chapter);
            default -> {
                if ("PLAN".equals(taskService.payload(task).path("action").asText())) chapter.setStatus(ChapterStatus.PLANNING);
            }
        }
    }

    private void persistPlan(AiTask task, StoryChapter chapter, ObjectNode data) {
        if(!ChapterTaskType.PLAN.equals(task.getTaskType())) throw new IllegalArgumentException("非计划任务不能保存计划");
        JsonNode plan=data.get("plan"); validatePlan(task,plan);
        chapter.setPlanJson(support.write(plan)); chapter.setPlanStatus("READY"); chapter.setStatus(ChapterStatus.PLAN_READY);
        chapter.setTitle(text(plan,"chapterTitle",chapter.getTitle())); chapter.setUpdatedTime(LocalDateTime.now()); chapters.updateById(chapter);
    }
    private void validatePlan(AiTask task, JsonNode plan) {
        if(plan==null||!plan.isObject()) throw new IllegalArgumentException("plan 必须是对象");
        JsonNode scenes=plan.get("scenes"); if(scenes==null||!scenes.isArray()||scenes.size()<3||scenes.size()>6)
            throw new IllegalArgumentException("章节计划必须包含 3 至 6 个场景");
        int target=plan.path("targetLength").asInt(0); if(target<800||target>8000)
            throw new IllegalArgumentException("targetLength 必须在 800 到 8000 之间");
        validateOutlineCoverage(task,plan,scenes);
        Set<String> known=knownCharacters(task);
        for(JsonNode scene:scenes){
            if(!StringUtils.hasText(text(scene,"protagonistGoal",null))||!StringUtils.hasText(text(scene,"opposingForce",null)))
                throw new IllegalArgumentException("每个场景必须包含目标和阻力");
            JsonNode characters=scene.get("characters");
            if(characters!=null&&characters.isArray()&&!known.isEmpty()) for(JsonNode name:characters)
                if(!known.contains(name.asText())) throw new IllegalArgumentException("章节计划包含未知角色: "+name.asText());
        }
    }
    private void validateOutlineCoverage(AiTask task,JsonNode plan,JsonNode scenes){
        JsonNode currentNodes=taskService.payload(task).get("currentOutlineNodes");
        if(currentNodes==null||!currentNodes.isArray()||currentNodes.size()!=2)
            throw new IllegalArgumentException("章节任务必须包含恰好两个 currentOutlineNodes");
        String chapterGoal=compact(text(plan,"chapterGoal",""));
        StringBuilder sceneContract=new StringBuilder();
        for(JsonNode scene:scenes){
            appendCompact(sceneContract,text(scene,"protagonistGoal",""));
            appendCompact(sceneContract,text(scene,"visibleConflict",""));
            appendCompact(sceneContract,text(scene,"informationRevealed",""));
            appendCompact(sceneContract,text(scene,"setupOrPayoff",""));
            appendCompact(sceneContract,text(scene,"exitHook",""));
        }
        for(int index=0;index<2;index++){
            JsonNode node=currentNodes.get(index);
            String event=stableAnchor(text(node,"event",""));
            String goal=stableAnchor(text(node,"protagonistGoal",text(node,"protagonist_goal","")));
            if(!StringUtils.hasText(event)||!StringUtils.hasText(goal))
                throw new IllegalArgumentException("当前大纲节点 "+(index+1)+" 缺少 event 或 protagonistGoal");
            if(!chapterGoal.contains(event)||!chapterGoal.contains(goal))
                throw new IllegalArgumentException("chapterGoal 未覆盖当前大纲节点 "+(index+1)+" 的事件和目标");
            String contract=sceneContract.toString();
            if(!contract.contains(event)||!contract.contains(goal))
                throw new IllegalArgumentException("场景未覆盖当前大纲节点 "+(index+1)+" 的事件和目标");
        }
    }
    private String stableAnchor(String value){
        String compact=compact(value);
        return compact.codePoints().limit(24).collect(StringBuilder::new,
                StringBuilder::appendCodePoint,StringBuilder::append).toString();
    }
    private String compact(String value){return value==null?"":value.replaceAll("\\s+","");}
    private void appendCompact(StringBuilder target,String value){target.append(compact(value));}
    private Set<String> knownCharacters(AiTask task) {
        Set<String> result=new HashSet<>(); JsonNode values=taskService.payload(task).get("characters");
        if(values!=null&&values.isArray()) for(JsonNode value:values){
            if(value.isTextual())result.add(value.asText());
            else {String name=text(value,"name",text(value,"characterName",null));if(name!=null)result.add(name);}
        }
        return result;
    }
    private StoryChapterVersion persistGeneratedVersion(AiTask task, StoryChapter chapter, ObjectNode data,
            String source,String chapterStatus,String eventType){
        String content=text(data,"content",text(data,"draftContent",null)); if(!StringUtils.hasText(content)) return null;
        StoryChapterVersion current=chapter.getCurrentVersionId()==null?null:versions.selectById(chapter.getCurrentVersionId());
        if(current!=null&&!task.getId().equals(current.getAiTaskId())
                && !isExpectedFinalizeBase(task,current)){
            data.put("staleResultDiscarded",true);
            data.put("preservedVersionId",current.getId());
            data.put("versionConflictCode","CHAPTER_VERSION_CONFLICT");
            return current;
        }
        String contentHash=support.sha256(content);
        if(current!=null&&current.getContentHash().equals(contentHash)
                && task.getId().equals(current.getAiTaskId())){
            if("HUMAN_REVIEW_REQUIRED".equals(eventType)) attachReview(current,task,data.get("review"));
            return current;
        }
        String key="ce:"+task.getId()+":"+eventType+":"+data.path("revisionCount").asInt(0)+":"
                +contentHash.substring(0,16);
        return versionService.createAndAdvance(chapter,source,content,chapter.getCurrentVersionId(),task.getId(),key,
                trim(data.path("promptVersion").asText(null)),trim(data.path("modelName").asText(null)),data.get("review"),
                eventType,task.getUserId(),chapterStatus);
    }
    private void attachReview(StoryChapterVersion current,AiTask task,JsonNode review){
        if(review==null||review.isNull()||current.getReviewJson()!=null)return;
        String reviewJson=support.write(review);
        if(versions.attachReviewIfAbsent(current.getId(),task.getId(),current.getContentHash(),reviewJson)==1)
            current.setReviewJson(reviewJson);
    }
    private boolean isExpectedFinalizeBase(AiTask task,StoryChapterVersion current){
        return ChapterTaskType.FINALIZE.equals(task.getTaskType())
                && taskService.payload(task).path("baseVersionId").asLong(-1)==current.getId();
    }
    private void persistProposal(AiTask task, StoryChapter chapter, ObjectNode data) {
        if(!ChapterTaskType.REWRITE.equals(task.getTaskType())) throw new IllegalArgumentException("非改写任务不能保存建议");
        Long proposalId=taskService.payload(task).path("proposalId").canConvertToLong()
                ?taskService.payload(task).path("proposalId").asLong():null;
        RewriteProposal proposal=proposalId==null?null:proposals.selectByIdForUpdate(proposalId);
        if(proposal==null||!chapter.getId().equals(proposal.getChapterId())||!task.getId().equals(proposal.getAiTaskId()))
            throw new IllegalArgumentException("改写任务缺少对应 proposal");
        if(!"PENDING".equals(proposal.getStatus())) {
            data.put("proposalId",proposal.getId()); data.put("baseVersionId",proposal.getBaseVersionId()); return;
        }
        if(data.has("chapterVersionId")&&!proposal.getBaseVersionId().equals(data.path("chapterVersionId").asLong()))
            throw new IllegalArgumentException("改写结果版本不一致");
        String original=text(data,"originalText",proposal.getSelectedText());
        String hash=text(data,"selectedTextHash",proposal.getSelectedTextHash());
        if(!proposal.getSelectedText().equals(original)||!proposal.getSelectedTextHash().equalsIgnoreCase(hash))
            throw new IllegalArgumentException("改写结果选区哈希不一致");
        String replacement=text(data,"replacementText",null); if(!StringUtils.hasText(replacement))
            throw new IllegalArgumentException("改写建议正文为空");
        proposal.setReplacementText(replacement); proposal.setReplacementHash(support.sha256(replacement));
        proposal.setReason(truncate(text(data,"reason","AI 局部改写"),1000)); proposal.setStatus("READY"); proposals.updateById(proposal);
        data.put("proposalId",proposal.getId()); data.put("baseVersionId",proposal.getBaseVersionId());
        data.put("startOffset",proposal.getStartOffset()); data.put("endOffset",proposal.getEndOffset());
        data.put("generationNo",proposal.getGenerationNo()); data.put("replacementTextHash",proposal.getReplacementHash());
    }
    private void persistFinal(AiTask task, StoryChapter chapter, ObjectNode data) {
        if(!ChapterTaskType.FINALIZE.equals(task.getTaskType())||!taskService.payload(task).path("approved").asBoolean(false))
            throw new IllegalArgumentException("非批准任务不能写入正式章节");
        String key="ce:"+task.getId()+":FINAL";
        StoryChapterVersion existing=versions.selectByIdempotencyKey(chapter.getId(),key);
        if(existing!=null){
            memoryService.persistApproval(chapter,existing,data.get("summary"),data.get("memoryUpdate"));
            if(!ChapterStatus.APPROVED.equals(chapter.getStatus())){
                chapter.setCurrentVersionId(existing.getId());chapter.setStatus(ChapterStatus.APPROVED);
                chapter.setWordCount(versionService.wordCount(existing.getContent()));
                chapter.setApprovedTime(LocalDateTime.now());chapter.setUpdatedTime(LocalDateTime.now());chapters.updateById(chapter);
            }
            data.put("approvedVersionId",existing.getId());data.put("chapterId",chapter.getId());return;
        }
        Long baseId=taskService.payload(task).path("baseVersionId").asLong();
        if(chapter.getCurrentVersionId()==null||!chapter.getCurrentVersionId().equals(baseId))
            throw new IllegalStateException("批准期间章节版本发生变化");
        StoryChapterVersion base=versions.selectById(baseId); String content=text(data,"content",base.getContent());
        StoryChapterVersion approved=versionService.createAndAdvance(chapter,"APPROVED",content,baseId,task.getId(),key,
                trim(data.path("promptVersion").asText(null)),trim(data.path("modelName").asText(null)),data.get("review"),
                "用户批准章节",task.getUserId(),ChapterStatus.APPROVED);
        memoryService.persistApproval(chapter,approved,data.get("summary"),data.get("memoryUpdate"));
        chapter.setStatus(ChapterStatus.APPROVED); chapter.setApprovedTime(LocalDateTime.now());
        chapter.setUpdatedTime(LocalDateTime.now()); chapters.updateById(chapter);
        data.put("approvedVersionId",approved.getId()); data.put("chapterId",chapter.getId());
    }
    private void persistFailure(AiTask task, StoryChapter chapter) {
        if(ChapterTaskType.REWRITE.equals(task.getTaskType())) return;
        if(ChapterTaskType.FINALIZE.equals(task.getTaskType())) chapter.setStatus(ChapterStatus.REVIEW_REQUIRED);
        else chapter.setStatus(ChapterStatus.FAILED);
        if(ChapterTaskType.PLAN.equals(task.getTaskType())) chapter.setPlanStatus("FAILED");
        chapter.setUpdatedTime(LocalDateTime.now()); chapters.updateById(chapter);
    }
    private void applyModelUsage(AiTask task,ObjectNode data){
        JsonNode calls=data.get("modelCalls"); if(calls==null||!calls.isArray())return;
        long in=0,out=0,duration=0;String model=null,prompt=null;
        for(JsonNode call:calls){in+=call.path("inputTokens").asLong(0);out+=call.path("outputTokens").asLong(0);
            duration+=call.path("durationMs").asLong(0); if(model==null)model=text(call,"modelName",text(call,"model",null));
            if(prompt==null)prompt=text(call,"promptVersion",null);}
        task.setInputTokens(in);task.setOutputTokens(out);task.setDurationMs(duration);
        task.setModelName(truncate(model,100));task.setPromptVersion(truncate(prompt,32));
    }
    private void trimEvents(Long taskId){
        int keep=properties.eventRetentionPerTask();
        List<Long> cutoff=events.selectObjs(Wrappers.<AiTaskEvent>query().select("id").eq("task_id",taskId)
                .orderByDesc("id").last("LIMIT 1 OFFSET "+(keep-1))).stream().map(v->((Number)v).longValue()).toList();
        if(!cutoff.isEmpty()) events.delete(Wrappers.<AiTaskEvent>lambdaQuery()
                .eq(AiTaskEvent::getTaskId,taskId).lt(AiTaskEvent::getId,cutoff.get(0)));
    }
    private TaskEventResponse response(AiTaskEvent event,AiTask task,StoryChapter chapter){
        if(task==null)task=tasks.selectById(event.getTaskId());
        if(chapter==null&&task!=null)chapter=chapters.selectById(task.getChapterId());
        return new TaskEventResponse(event.getRedisEventId(),event.getTaskId(),task==null?null:task.getStoryId(),
                task==null?null:task.getChapterId(),chapter==null?null:chapter.getChapterNo(),event.getEventType(),
                event.getSequenceNo(),event.getStatus(),event.getCurrentNode(),event.getProgress(),support.read(event.getDataJson()),
                task==null?null:task.getErrorCode(),task==null?null:task.getErrorMessage(),event.getCreatedTime());
    }
    public TaskEventResponse response(AiTaskEvent event){return response(event,null,null);}
    private void validateIdentity(AiTask task,Map<String,String> f){
        if(!task.getStoryId().equals(requiredLong(f,"storyId"))||!task.getChapterId().equals(requiredLong(f,"chapterId")))
            throw new IllegalArgumentException("事件业务身份与任务不一致");
        String key=required(f,"idempotencyKey");if(!key.equals(task.getIdempotencyKey()))throw new IllegalArgumentException("事件幂等键不一致");
        String thread=trim(f.get("threadId"));if(thread!=null&&task.getThreadId()!=null&&!thread.equals(task.getThreadId()))
            throw new IllegalArgumentException("事件 threadId 不一致");
    }
    private String identityThread(AiTask task,Map<String,String> f){String v=trim(f.get("threadId"));return v==null?task.getThreadId():v;}
    private String normalizeStatus(String raw,String type){String status=trim(raw);
        if(status==null)status=switch(type){case"CHAPTER_PLAN_READY","REWRITE_PROPOSAL_READY","FINAL_READY"->AiTaskStatus.SUCCESS;
            case"HUMAN_REVIEW_REQUIRED"->AiTaskStatus.REVIEW_REQUIRED;case"TASK_FAILED"->AiTaskStatus.FAILED;default->AiTaskStatus.RUNNING;};
        status=status.toUpperCase(Locale.ROOT);if(!AiTaskStatus.isWorkflowStatus(status))throw new IllegalArgumentException("无效任务状态");return status;}
    private String required(Map<String,String>f,String k){String v=trim(f.get(k));if(v==null)throw new IllegalArgumentException(k+" 不能为空");return v;}
    private Long requiredLong(Map<String,String>f,String k){try{return Long.valueOf(required(f,k));}catch(NumberFormatException e){throw new IllegalArgumentException(k+" 格式错误",e);}}
    private Integer optionalInt(String v){if(trim(v)==null)return null;try{return Integer.valueOf(v.trim());}catch(NumberFormatException e){throw new IllegalArgumentException("整数字段格式错误",e);}}
    private String trim(String v){return StringUtils.hasText(v)?v.trim():null;}
    private String truncate(String v,int max){return v==null||v.length()<=max?v:v.substring(0,max);}
    private String text(JsonNode n,String f,String d){JsonNode v=n==null?null:n.get(f);return v==null||v.isNull()?d:v.asText();}
    public record ProcessedEvent(boolean persisted,TaskEventResponse event){}
}
