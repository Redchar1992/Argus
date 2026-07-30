package com.storyforge.chapter.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.chapter.ChapterContentPolicy;
import com.storyforge.chapter.ChapterStatus;
import com.storyforge.chapter.ChapterTaskType;
import com.storyforge.chapter.dto.AcceptProposalRequest;
import com.storyforge.chapter.dto.ApprovePlanRequest;
import com.storyforge.chapter.dto.RewriteSelectionRequest;
import com.storyforge.chapter.dto.SaveChapterContentRequest;
import com.storyforge.chapter.entity.RewriteProposal;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import com.storyforge.chapter.mapper.RewriteProposalMapper;
import com.storyforge.chapter.mapper.StoryChapterMapper;
import com.storyforge.chapter.mapper.StoryChapterVersionMapper;
import com.storyforge.chapter.vo.ChapterVersionResponse;
import com.storyforge.chapter.vo.RewriteProposalResponse;
import com.storyforge.common.exception.ApiException;
import com.storyforge.story.StoryProject;
import com.storyforge.story.StoryProjectMapper;
import com.storyforge.story.StoryStatus;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import com.storyforge.task.AiTaskStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ChapterPersistenceService {
    private final StoryProjectMapper stories;
    private final StoryChapterMapper chapters;
    private final StoryChapterVersionMapper versions;
    private final RewriteProposalMapper proposals;
    private final ChapterContextAssembler contextAssembler;
    private final ChapterTaskService taskService;
    private final ChapterVersionService versionService;
    private final ChapterSupport support;
    private final AiTaskMapper aiTasks;

    public ChapterPersistenceService(StoryProjectMapper stories, StoryChapterMapper chapters,
            StoryChapterVersionMapper versions, RewriteProposalMapper proposals,
            ChapterContextAssembler contextAssembler, ChapterTaskService taskService,
            ChapterVersionService versionService, ChapterSupport support, AiTaskMapper aiTasks) {
        this.stories=stories; this.chapters=chapters; this.versions=versions; this.proposals=proposals;
        this.contextAssembler=contextAssembler; this.taskService=taskService;
        this.versionService=versionService; this.support=support;
        this.aiTasks=aiTasks;
    }

    @Transactional
    public PreparedCommand preparePlan(Long userId, Long storyId, int chapterNo, int targetLength) {
        if (chapterNo < 1 || chapterNo > 200) bad("INVALID_CHAPTER_NO", "chapterNo 必须在 1 到 200 之间");
        StoryProject story = requireOwnedStoryForUpdate(userId, storyId);
        if (!StoryStatus.WORKFLOW_COMPLETED.equals(story.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "OUTLINE_NOT_APPROVED", "请先批准人物与大纲");
        }
        if (chapterNo > 1) {
            StoryChapter previous = chapters.selectOne(Wrappers.<StoryChapter>lambdaQuery()
                    .eq(StoryChapter::getStoryId, storyId).eq(StoryChapter::getChapterNo, chapterNo - 1));
            if (previous == null || !ChapterStatus.APPROVED.equals(previous.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "PREVIOUS_CHAPTER_NOT_APPROVED", "请先批准上一章");
            }
        }
        StoryChapter chapter = chapters.selectByStoryAndNoForUpdate(storyId, chapterNo);
        if (chapter == null) {
            chapter = new StoryChapter(); LocalDateTime now = LocalDateTime.now();
            chapter.setStoryId(storyId); chapter.setChapterNo(chapterNo); chapter.setStatus(ChapterStatus.DRAFT);
            chapter.setPlanStatus("NOT_PLANNED"); chapter.setWordCount(0); chapter.setRowVersion(0L);
            chapter.setCreatedTime(now); chapter.setUpdatedTime(now); chapters.insert(chapter);
        }
        if ("APPROVED".equals(chapter.getPlanStatus()) || ChapterStatus.APPROVED.equals(chapter.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_PLAN_LOCKED", "章节计划已确认，不能重新生成");
        }
        ObjectNode payload = contextAssembler.assemble(story, chapter, targetLength);
        String key = "c" + chapter.getId() + ":PLAN:1";
        AiTask task = taskService.create(userId, storyId, chapter.getId(), ChapterTaskType.PLAN,
                "PLAN", key, payload, null);
        if (!AiTaskStatusLike.isTerminal(task.getStatus())) {
            chapter.setStatus(ChapterStatus.PLANNING); chapter.setPlanStatus("GENERATING");
            chapter.setUpdatedTime(LocalDateTime.now()); chapters.updateById(chapter);
        }
        return new PreparedCommand(task, chapter, "PLAN", payload);
    }

    @Transactional
    public StoryChapter approvePlan(Long userId, Long storyId, int chapterNo, ApprovePlanRequest request) {
        requireOwnedStoryForUpdate(userId, storyId);
        StoryChapter chapter = chapters.selectByStoryAndNoForUpdate(storyId, chapterNo);
        if (chapter == null) notFound();
        if (!ChapterStatus.PLAN_READY.equals(chapter.getStatus()) || !"READY".equals(chapter.getPlanStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_PLAN_NOT_READY", "章节计划尚未生成完成");
        }
        String actualHash = support.sha256(chapter.getPlanJson());
        if (request != null && StringUtils.hasText(request.planHash())
                && !actualHash.equalsIgnoreCase(request.planHash().trim())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_PLAN_CONFLICT", "章节计划已经变化，请刷新后确认");
        }
        chapter.setPlanStatus("APPROVED"); chapter.setStatus(ChapterStatus.PLAN_APPROVED);
        chapter.setUpdatedTime(LocalDateTime.now()); chapters.updateById(chapter);
        return chapter;
    }

    @Transactional
    public PreparedCommand prepareGenerate(Long userId, Long storyId, int chapterNo) {
        StoryProject story = requireOwnedStoryForUpdate(userId, storyId);
        StoryChapter chapter = chapters.selectByStoryAndNoForUpdate(storyId, chapterNo);
        if (chapter == null) notFound();
        if (!"APPROVED".equals(chapter.getPlanStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_PLAN_NOT_APPROVED", "请先确认章节计划");
        }
        if (ChapterStatus.APPROVED.equals(chapter.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_ALREADY_APPROVED", "章节已经批准");
        }
        ObjectNode payload = contextAssembler.assemble(story, chapter,
                support.read(chapter.getPlanJson()).path("targetLength").asInt(1600));
        payload.set("chapterPlan", support.read(chapter.getPlanJson()));
        String key = "c" + chapter.getId() + ":GEN:" + support.sha256(chapter.getPlanJson()).substring(0, 20);
        AiTask task = taskService.create(userId, storyId, chapter.getId(), ChapterTaskType.GENERATE,
                "GENERATE", key, payload, null);
        if (!AiTaskStatusLike.isTerminal(task.getStatus())) {
            chapter.setStatus(ChapterStatus.GENERATING); chapter.setUpdatedTime(LocalDateTime.now());
            chapters.updateById(chapter);
        }
        return new PreparedCommand(task, chapter, "GENERATE", payload);
    }

    @Transactional
    public ChapterVersionResponse saveContent(Long userId, Long chapterId, SaveChapterContentRequest request) {
        StoryChapter chapter = requireOwnedChapterForUpdate(userId, chapterId);
        ensureEditable(chapter);
        if (chapter.getCurrentVersionId() == null || !chapter.getCurrentVersionId().equals(request.baseVersionId())) {
            versionConflict();
        }
        StoryChapterVersion base = versionService.requireInChapter(chapterId, request.baseVersionId());
        if (StringUtils.hasText(request.baseContentHash())
                && !base.getContentHash().equalsIgnoreCase(request.baseContentHash().trim())) versionConflict();
        String content = request.content();
        if (base.getContent().equals(content)) return versionService.toResponse(base);
        String key = "c" + chapterId + ":EDIT:" + base.getId() + ":" + support.sha256(content).substring(0, 24);
        StoryChapterVersion version = versionService.createAndAdvance(chapter, "USER_EDIT", content,
                base.getId(), null, key, null, null, null, "用户人工编辑", userId,
                ChapterStatus.REVIEW_REQUIRED);
        return versionService.toResponse(version);
    }

    @Transactional
    public PreparedRewrite prepareRewrite(Long userId, Long chapterId, RewriteSelectionRequest request, Integer generationNo) {
        StoryChapter chapter = requireOwnedChapterForUpdate(userId, chapterId); ensureEditable(chapter);
        if (chapter.getCurrentVersionId() == null || !chapter.getCurrentVersionId().equals(request.chapterVersionId())) versionConflict();
        StoryChapterVersion base = versionService.requireInChapter(chapterId, request.chapterVersionId());
        validateSelection(base, request);
        int gen = generationNo == null ? 1 : generationNo;
        String fingerprint = support.sha256(base.getId() + ":" + request.startOffset() + ":" + request.endOffset()
                + ":" + request.selectedTextHash().toLowerCase(Locale.ROOT) + ":" + request.action()
                + ":" + nullToEmpty(request.customInstruction()) + ":" + gen);
        String key = "c" + chapterId + ":RW:" + fingerprint.substring(0, 32);
        RewriteProposal proposal = proposals.selectOne(Wrappers.<RewriteProposal>lambdaQuery()
                .eq(RewriteProposal::getIdempotencyKey, key));
        if (proposal != null) {
            AiTask previous = taskService.find(userId, key);
            if (previous == null) throw new IllegalStateException("改写建议缺少对应 AI 任务");
            JsonNode existingPayload = taskService.payload(previous);
            AiTask task = taskService.create(userId, chapter.getStoryId(), chapterId, ChapterTaskType.REWRITE,
                    "REWRITE_SELECTION", key, existingPayload, null);
            if (!task.getId().equals(proposal.getAiTaskId())) {
                proposal.setAiTaskId(task.getId());
                proposals.updateById(proposal);
            }
            return new PreparedRewrite(task, proposal, chapter, existingPayload);
        }
        proposal = new RewriteProposal(); proposal.setChapterId(chapterId); proposal.setBaseVersionId(base.getId());
        proposal.setIdempotencyKey(key); proposal.setGenerationNo(gen); proposal.setStartOffset(request.startOffset());
        proposal.setEndOffset(request.endOffset()); proposal.setSelectedText(request.selectedText());
        proposal.setSelectedTextHash(request.selectedTextHash().toLowerCase(Locale.ROOT));
        proposal.setActionType(request.action().trim().toUpperCase(Locale.ROOT));
        proposal.setCustomInstruction(trimToNull(request.customInstruction())); proposal.setStatus("PENDING");
        proposal.setCreatedBy(userId); proposal.setCreatedTime(LocalDateTime.now()); proposals.insert(proposal);
        ObjectNode payload = support.mapper().createObjectNode();
        payload.put("proposalId", proposal.getId()); payload.put("chapterVersionId", base.getId());
        int codePointStart=base.getContent().codePointCount(0,request.startOffset());
        int codePointEnd=codePointStart+request.selectedText().codePointCount(0,request.selectedText().length());
        payload.put("startOffset", codePointStart); payload.put("endOffset", codePointEnd);
        payload.put("selectedText", request.selectedText()); payload.put("selectedTextHash", request.selectedTextHash());
        payload.put("action", proposal.getActionType()); payload.put("customInstruction", nullToEmpty(request.customInstruction()));
        payload.put("chapterContent", base.getContent());
        AiTask task = taskService.create(userId, chapter.getStoryId(), chapterId, ChapterTaskType.REWRITE,
                "REWRITE_SELECTION", key, payload, null);
        proposal.setAiTaskId(task.getId()); proposals.updateById(proposal);
        return new PreparedRewrite(task, proposal, chapter, payload);
    }

    @Transactional
    public PreparedRewrite regenerateRewrite(Long userId, Long chapterId, Long proposalId) {
        RewriteProposal old = requireOwnedProposalForUpdate(userId, chapterId, proposalId);
        if (!"READY".equals(old.getStatus()) && !"REJECTED".equals(old.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "REWRITE_PROPOSAL_NOT_READY", "当前建议不能再次生成");
        }
        RewriteSelectionRequest request = new RewriteSelectionRequest(old.getBaseVersionId(), old.getStartOffset(),
                old.getEndOffset(), old.getSelectedText(), old.getSelectedTextHash(), old.getActionType(),
                old.getCustomInstruction());
        // Re-entering through the proxy is unnecessary: the current transaction and row locks remain active.
        return prepareRewrite(userId, chapterId, request, old.getGenerationNo() + 1);
    }

    @Transactional
    public ChapterVersionResponse acceptProposal(Long userId, Long chapterId, Long proposalId,
                                                  AcceptProposalRequest request) {
        RewriteProposal proposal = requireOwnedProposalForUpdate(userId, chapterId, proposalId);
        if (!"READY".equals(proposal.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "REWRITE_PROPOSAL_NOT_READY", "AI 改写建议尚未就绪或已处理");
        }
        StoryChapter chapter = chapters.selectByIdForUpdate(chapterId); ensureEditable(chapter);
        Long expectedBase = request != null && request.baseVersionId() != null
                ? request.baseVersionId() : proposal.getBaseVersionId();
        if (!proposal.getBaseVersionId().equals(expectedBase)
                || !expectedBase.equals(chapter.getCurrentVersionId())) versionConflict();
        StoryChapterVersion base = versionService.requireInChapter(chapterId, expectedBase);
        if (request != null && StringUtils.hasText(request.baseContentHash())
                && !base.getContentHash().equalsIgnoreCase(request.baseContentHash().trim())) versionConflict();
        validateStoredSelection(base, proposal);
        if (!StringUtils.hasText(proposal.getReplacementText())) {
            throw new ApiException(HttpStatus.CONFLICT, "REWRITE_REPLACEMENT_MISSING", "AI 改写结果为空");
        }
        String content = base.getContent().substring(0, proposal.getStartOffset()) + proposal.getReplacementText()
                + base.getContent().substring(proposal.getEndOffset());
        ChapterContentPolicy.requireValidLength(content);
        String key = "c" + chapterId + ":ACCEPT:" + proposalId;
        StoryChapterVersion version = versionService.createAndAdvance(chapter, "AI_SELECTION_REWRITE", content,
                base.getId(), proposal.getAiTaskId(), key, null, null, null,
                proposal.getReason(), userId, ChapterStatus.REVIEW_REQUIRED);
        proposal.setStatus("ACCEPTED"); proposal.setResolvedVersionId(version.getId());
        proposal.setResolvedTime(LocalDateTime.now()); proposals.updateById(proposal);
        return versionService.toResponse(version);
    }

    @Transactional
    public RewriteProposal rejectProposal(Long userId, Long chapterId, Long proposalId) {
        RewriteProposal proposal = requireOwnedProposalForUpdate(userId, chapterId, proposalId);
        if (!"PENDING".equals(proposal.getStatus()) && !"READY".equals(proposal.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "REWRITE_PROPOSAL_ALREADY_RESOLVED", "AI 改写建议已处理");
        }
        proposal.setStatus("REJECTED"); proposal.setResolvedTime(LocalDateTime.now()); proposals.updateById(proposal);
        return proposal;
    }

    @Transactional
    public ChapterVersionResponse restore(Long userId, Long chapterId, Long targetVersionId) {
        StoryChapter chapter = requireOwnedChapterForUpdate(userId, chapterId); ensureEditable(chapter);
        StoryChapterVersion target = versionService.requireInChapter(chapterId, targetVersionId);
        Long base = chapter.getCurrentVersionId();
        String key = "c" + chapterId + ":RESTORE:" + base + ":" + targetVersionId;
        return versionService.toResponse(versionService.createAndAdvance(chapter, "RESTORE", target.getContent(),
                base, null, key, target.getPromptVersion(), target.getModelName(),
                support.read(target.getReviewJson()), "恢复到版本 " + target.getVersionNo(), userId,
                ChapterStatus.REVIEW_REQUIRED));
    }

    @Transactional
    public PreparedCommand prepareFinalize(Long userId, Long chapterId, boolean approved, String notes) {
        StoryChapter chapter = requireOwnedChapterForUpdate(userId, chapterId);
        if (chapter.getCurrentVersionId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_REVIEW_NOT_READY", "章节尚未进入人工审核");
        }
        if (!approved && !StringUtils.hasText(notes)) {
            bad("CHAPTER_REVIEW_NOTES_REQUIRED", "要求修改时必须填写具体意见");
        }
        StoryChapterVersion current = versionService.requireInChapter(chapterId, chapter.getCurrentVersionId());
        ObjectNode payload = support.mapper().createObjectNode(); payload.put("approved", approved);
        payload.put("notes", nullToEmpty(notes)); payload.put("currentContent", current.getContent());
        payload.put("baseVersionId", current.getId()); payload.set("chapterPlan", support.read(chapter.getPlanJson()));
        String key = "c" + chapterId + ":FIN:" + current.getId() + ":" + (approved ? "A" : "R") + ":"
                + support.sha256(nullToEmpty(notes)).substring(0, 12);
        AiTask previous = taskService.find(userId, key);
        boolean retryingFailedFinalize = ChapterStatus.FAILED.equals(chapter.getStatus())
                && previous != null && AiTaskStatusLike.isFailed(previous.getStatus());
        if (!ChapterStatus.REVIEW_REQUIRED.equals(chapter.getStatus()) && !retryingFailedFinalize) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_REVIEW_NOT_READY", "章节尚未进入人工审核");
        }
        AiTask sourceTask = requireChapterThread(current);
        ensureFinalizeReady(chapter.getId(), sourceTask);
        AiTask task = taskService.create(userId, chapter.getStoryId(), chapterId, ChapterTaskType.FINALIZE,
                "FINALIZE", key, payload, sourceTask.getId(), sourceTask.getThreadId());
        chapter.setStatus(approved ? ChapterStatus.FINALIZING : ChapterStatus.GENERATING);
        chapter.setUpdatedTime(LocalDateTime.now()); chapters.updateById(chapter);
        return new PreparedCommand(task, chapter, "FINALIZE", payload);
    }

    private AiTask requireChapterThread(StoryChapterVersion version) {
        StoryChapterVersion cursor = version;
        while (cursor != null) {
            if (cursor.getAiTaskId() != null) {
                AiTask task = aiTasks.selectById(cursor.getAiTaskId());
                if (task != null
                        && (ChapterTaskType.GENERATE.equals(task.getTaskType())
                        || ChapterTaskType.FINALIZE.equals(task.getTaskType()))
                        && StringUtils.hasText(task.getThreadId())) {
                    return task;
                }
            }
            cursor = cursor.getBaseVersionId() == null ? null : versions.selectById(cursor.getBaseVersionId());
        }
        throw new ApiException(
                HttpStatus.CONFLICT,
                "CHAPTER_THREAD_MISSING",
                "章节缺少可恢复的生成线程，请重新生成正文"
        );
    }

    private void ensureFinalizeReady(Long chapterId, AiTask sourceTask) {
        if (!AiTaskStatus.REVIEW_REQUIRED.equals(sourceTask.getStatus())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CHAPTER_SOURCE_TASK_NOT_READY",
                    "章节生成任务尚未进入人工审核阶段"
            );
        }
        Long activeCount=aiTasks.selectCount(Wrappers.<AiTask>lambdaQuery()
                .eq(AiTask::getChapterId,chapterId)
                .in(AiTask::getTaskType,List.of(ChapterTaskType.GENERATE,ChapterTaskType.FINALIZE))
                .in(AiTask::getStatus,List.of(AiTaskStatus.WAITING,AiTaskStatus.RUNNING)));
        if(activeCount!=null&&activeCount>0){
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "CHAPTER_TASK_ACTIVE",
                    "章节仍有生成或批准任务正在执行"
            );
        }
    }

    @Transactional
    public void markDispatchFailed(Long chapterId, String action) {
        StoryChapter chapter = chapters.selectByIdForUpdate(chapterId);
        if (chapter == null || ChapterStatus.APPROVED.equals(chapter.getStatus())) {
            return;
        }
        if ("REWRITE_SELECTION".equals(action)) {
            return;
        }
        chapter.setStatus("FINALIZE".equals(action) ? ChapterStatus.REVIEW_REQUIRED : ChapterStatus.FAILED);
        if ("PLAN".equals(action)) {
            chapter.setPlanStatus("FAILED");
        }
        chapter.setUpdatedTime(LocalDateTime.now());
        chapters.updateById(chapter);
    }

    public StoryChapter requireOwnedChapter(Long userId, Long chapterId) {
        StoryChapter chapter = chapters.selectById(chapterId);
        if (chapter == null) notFound();
        StoryProject story = stories.selectById(chapter.getStoryId());
        if (story == null || !story.getUserId().equals(userId)) forbidden();
        return chapter;
    }
    private StoryChapter requireOwnedChapterForUpdate(Long userId, Long chapterId) {
        StoryChapter chapter = chapters.selectByIdForUpdate(chapterId);
        if (chapter == null) notFound();
        StoryProject story = stories.selectById(chapter.getStoryId());
        if (story == null || !story.getUserId().equals(userId)) forbidden();
        return chapter;
    }
    private StoryProject requireOwnedStoryForUpdate(Long userId, Long storyId) {
        StoryProject story = stories.selectByIdForUpdate(storyId);
        if (story == null) throw new ApiException(HttpStatus.NOT_FOUND, "STORY_NOT_FOUND", "故事不存在");
        if (!story.getUserId().equals(userId)) forbidden();
        return story;
    }
    private RewriteProposal requireOwnedProposalForUpdate(Long userId, Long chapterId, Long proposalId) {
        requireOwnedChapter(userId, chapterId);
        RewriteProposal proposal = proposals.selectByIdForUpdate(proposalId);
        if (proposal == null || !chapterId.equals(proposal.getChapterId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REWRITE_PROPOSAL_NOT_FOUND", "AI 改写建议不存在");
        }
        return proposal;
    }
    private void validateSelection(StoryChapterVersion base, RewriteSelectionRequest request) {
        if (request.endOffset() <= request.startOffset() || request.endOffset() > base.getContent().length()) {
            bad("INVALID_SELECTION_RANGE", "选区范围无效");
        }
        String actual = base.getContent().substring(request.startOffset(), request.endOffset());
        if (!actual.equals(request.selectedText()) || !support.sha256(actual).equalsIgnoreCase(request.selectedTextHash())) {
            throw new ApiException(HttpStatus.CONFLICT, "SELECTION_HASH_CONFLICT", "选中文本已经变化，请重新选择");
        }
    }
    private void validateStoredSelection(StoryChapterVersion base, RewriteProposal proposal) {
        if (proposal.getEndOffset() > base.getContent().length()
                || proposal.getStartOffset() < 0 || proposal.getStartOffset() >= proposal.getEndOffset()) versionConflict();
        String actual = base.getContent().substring(proposal.getStartOffset(), proposal.getEndOffset());
        if (!actual.equals(proposal.getSelectedText())
                || !support.sha256(actual).equalsIgnoreCase(proposal.getSelectedTextHash())) versionConflict();
    }
    private void ensureEditable(StoryChapter chapter) {
        if (ChapterStatus.APPROVED.equals(chapter.getStatus()) || ChapterStatus.FINALIZING.equals(chapter.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_LOCKED", "章节已锁定，不能编辑");
        }
    }
    private void versionConflict() { throw new ApiException(HttpStatus.CONFLICT, "CHAPTER_VERSION_CONFLICT", "章节版本已经变化，请刷新后重试"); }
    private void forbidden() { throw new ApiException(HttpStatus.FORBIDDEN, "CHAPTER_FORBIDDEN", "无权访问该章节"); }
    private void notFound() { throw new ApiException(HttpStatus.NOT_FOUND, "CHAPTER_NOT_FOUND", "章节不存在"); }
    private void bad(String code, String message) { throw new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private String nullToEmpty(String value) { return value == null ? "" : value.trim(); }
    private String trimToNull(String value) { return StringUtils.hasText(value) ? value.trim() : null; }

    public record PreparedCommand(AiTask task, StoryChapter chapter, String action, JsonNode payload) { }
    public record PreparedRewrite(AiTask task, RewriteProposal proposal, StoryChapter chapter, JsonNode payload) { }
    private static final class AiTaskStatusLike {
        static boolean isTerminal(String status) { return "SUCCESS".equals(status) || "REVIEW_REQUIRED".equals(status); }
        static boolean isFailed(String status) { return "FAILED".equals(status); }
    }
}
