package com.storyforge.chapter.service;

import java.util.List;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.storyforge.chapter.dto.AcceptProposalRequest;
import com.storyforge.chapter.dto.ApprovePlanRequest;
import com.storyforge.chapter.dto.FinalizeChapterRequest;
import com.storyforge.chapter.dto.PlanChapterRequest;
import com.storyforge.chapter.dto.RewriteSelectionRequest;
import com.storyforge.chapter.dto.SaveChapterContentRequest;
import com.storyforge.chapter.entity.RewriteProposal;
import com.storyforge.chapter.entity.StoryChapter;
import com.storyforge.chapter.entity.StoryChapterVersion;
import com.storyforge.chapter.mapper.RewriteProposalMapper;
import com.storyforge.chapter.mapper.StoryChapterMapper;
import com.storyforge.chapter.mapper.StoryChapterVersionMapper;
import com.storyforge.chapter.vo.ChapterResponse;
import com.storyforge.chapter.vo.ChapterTaskResponse;
import com.storyforge.chapter.vo.ChapterVersionCompareResponse;
import com.storyforge.chapter.vo.ChapterVersionResponse;
import com.storyforge.chapter.vo.RewriteProposalResponse;
import com.storyforge.common.exception.ApiException;
import com.storyforge.story.StoryService;
import com.storyforge.task.AiTask;
import com.storyforge.task.AiTaskMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChapterApplicationService {
    private final ChapterPersistenceService persistence;
    private final ChapterTaskService tasks;
    private final StoryChapterMapper chapters;
    private final StoryChapterVersionMapper versions;
    private final RewriteProposalMapper proposals;
    private final ChapterVersionService versionService;
    private final StoryService storyService;
    private final ChapterSupport support;
    private final JdbcTemplate jdbc;
    private final AiTaskMapper aiTasks;

    public ChapterApplicationService(ChapterPersistenceService persistence, ChapterTaskService tasks,
            StoryChapterMapper chapters, StoryChapterVersionMapper versions, RewriteProposalMapper proposals,
            ChapterVersionService versionService, StoryService storyService, ChapterSupport support, JdbcTemplate jdbc,
            AiTaskMapper aiTasks) {
        this.persistence=persistence; this.tasks=tasks; this.chapters=chapters; this.versions=versions;
        this.proposals=proposals; this.versionService=versionService; this.storyService=storyService;
        this.support=support; this.jdbc=jdbc;
        this.aiTasks=aiTasks;
    }

    public ChapterTaskResponse plan(Long userId, Long storyId, int chapterNo, PlanChapterRequest request) {
        var prepared = persistence.preparePlan(userId, storyId, chapterNo,
                request == null ? 1600 : request.normalizedTargetLength());
        return dispatch(prepared);
    }
    public ChapterResponse approvePlan(Long userId, Long storyId, int chapterNo, ApprovePlanRequest request) {
        return toResponse(persistence.approvePlan(userId, storyId, chapterNo, request));
    }
    public ChapterTaskResponse generate(Long userId, Long storyId, int chapterNo) {
        return dispatch(persistence.prepareGenerate(userId, storyId, chapterNo));
    }
    public ChapterVersionResponse saveContent(Long userId, Long chapterId, SaveChapterContentRequest request) {
        return persistence.saveContent(userId, chapterId, request);
    }
    public ChapterTaskResponse rewrite(Long userId, Long chapterId, RewriteSelectionRequest request) {
        var prepared = persistence.prepareRewrite(userId, chapterId, request, null);
        return dispatch(prepared);
    }
    public ChapterTaskResponse regenerate(Long userId, Long chapterId, Long proposalId) {
        return dispatch(persistence.regenerateRewrite(userId, chapterId, proposalId));
    }
    public ChapterVersionResponse accept(Long userId, Long chapterId, Long proposalId, AcceptProposalRequest request) {
        return persistence.acceptProposal(userId, chapterId, proposalId, request);
    }
    public RewriteProposalResponse reject(Long userId, Long chapterId, Long proposalId) {
        return proposalResponse(persistence.rejectProposal(userId, chapterId, proposalId));
    }
    public ChapterVersionResponse restore(Long userId, Long chapterId, Long versionId) {
        return persistence.restore(userId, chapterId, versionId);
    }
    public ChapterTaskResponse finalizeChapter(Long userId, Long chapterId, FinalizeChapterRequest request) {
        boolean approved = request == null || request.isApproved();
        String notes = request == null ? "" : request.notes();
        return dispatch(persistence.prepareFinalize(userId, chapterId, approved, notes));
    }

    public List<ChapterResponse> list(Long userId, Long storyId) {
        storyService.requireOwned(userId, storyId);
        return chapters.selectList(Wrappers.<StoryChapter>lambdaQuery()
                .eq(StoryChapter::getStoryId, storyId).orderByAsc(StoryChapter::getChapterNo))
                .stream().map(this::toResponse).toList();
    }
    public ChapterResponse getByNo(Long userId, Long storyId, int chapterNo) {
        storyService.requireOwned(userId, storyId);
        StoryChapter chapter = chapters.selectOne(Wrappers.<StoryChapter>lambdaQuery()
                .eq(StoryChapter::getStoryId, storyId).eq(StoryChapter::getChapterNo, chapterNo));
        if (chapter == null) chapterNotFound();
        return toResponse(chapter);
    }
    public ChapterResponse get(Long userId, Long chapterId) {
        return toResponse(persistence.requireOwnedChapter(userId, chapterId));
    }
    public List<ChapterVersionResponse> versions(Long userId, Long chapterId) {
        persistence.requireOwnedChapter(userId, chapterId); return versionService.list(chapterId);
    }
    public ChapterVersionCompareResponse compare(Long userId, Long chapterId, Long from, Long to) {
        persistence.requireOwnedChapter(userId, chapterId); return versionService.compare(chapterId, from, to);
    }
    public RewriteProposalResponse proposal(Long userId, Long chapterId, Long proposalId) {
        persistence.requireOwnedChapter(userId, chapterId);
        RewriteProposal proposal = proposals.selectById(proposalId);
        if (proposal == null || !chapterId.equals(proposal.getChapterId())) proposalNotFound();
        return proposalResponse(proposal);
    }
    public List<RewriteProposalResponse> proposals(Long userId, Long chapterId) {
        persistence.requireOwnedChapter(userId, chapterId);
        return proposals.selectList(Wrappers.<RewriteProposal>lambdaQuery()
                .eq(RewriteProposal::getChapterId, chapterId).orderByDesc(RewriteProposal::getCreatedTime))
                .stream().map(this::proposalResponse).toList();
    }

    private ChapterTaskResponse dispatch(ChapterPersistenceService.PreparedCommand prepared) {
        try { return tasks.dispatch(prepared.task(), prepared.chapter().getChapterNo(), prepared.action(), prepared.payload()); }
        catch (ApiException exception) {
            if (ChapterTaskService.DISPATCH_ERROR.equals(exception.getCode()))
                persistence.markDispatchFailed(prepared.chapter().getId(), prepared.action());
            throw exception;
        }
    }
    private ChapterTaskResponse dispatch(ChapterPersistenceService.PreparedRewrite prepared) {
        try { return tasks.dispatch(prepared.task(), prepared.chapter().getChapterNo(), "REWRITE_SELECTION", prepared.payload()); }
        catch (ApiException exception) {
            if (ChapterTaskService.DISPATCH_ERROR.equals(exception.getCode()))
                persistence.markDispatchFailed(prepared.chapter().getId(), "REWRITE_SELECTION");
            throw exception;
        }
    }
    public ChapterResponse toResponse(StoryChapter chapter) {
        StoryChapterVersion current = chapter.getCurrentVersionId() == null ? null : versions.selectById(chapter.getCurrentVersionId());
        ChapterVersionResponse currentResponse = currentVersionResponse(current);
        AiTask activeTask=aiTasks.selectOne(Wrappers.<AiTask>lambdaQuery()
                .eq(AiTask::getChapterId,chapter.getId())
                .in(AiTask::getTaskType,List.of("CHAPTER_PLAN","CHAPTER_GENERATE","CHAPTER_FINALIZE"))
                .orderByDesc(AiTask::getCreatedTime).orderByDesc(AiTask::getId).last("LIMIT 1"));
        JsonNode summary = null;
        List<JsonNode> summaryRows = jdbc.query("""
                SELECT summary, main_events_json, character_changes_json,
                       opened_threads_json, resolved_threads_json, ending_hook
                FROM story_chapter_summary WHERE chapter_id=?
                ORDER BY created_time DESC, id DESC LIMIT 1
                """, (rs, row) -> {
                    var node=support.mapper().createObjectNode();
                    node.put("chapterNo",chapter.getChapterNo());node.put("summary",rs.getString("summary"));
                    node.set("mainEvents",jsonArray(rs.getString("main_events_json")));
                    node.set("characterChanges",jsonArray(rs.getString("character_changes_json")));
                    node.set("openedThreads",jsonArray(rs.getString("opened_threads_json")));
                    node.set("resolvedThreads",jsonArray(rs.getString("resolved_threads_json")));
                    node.put("endingHook",rs.getString("ending_hook"));return node;
                }, chapter.getId());
        if (!summaryRows.isEmpty()) {
            summary = summaryRows.get(0);
        }
        return new ChapterResponse(chapter.getId(), chapter.getStoryId(), chapter.getChapterNo(), chapter.getTitle(),
                chapter.getStatus(), chapter.getPlanStatus(), support.read(chapter.getPlanJson()),
                chapter.getPlanJson() == null ? null : support.sha256(chapter.getPlanJson()), chapter.getWordCount(),
                chapter.getRowVersion(), chapter.getCurrentVersionId(), currentResponse,
                activeTask==null?null:activeTask.getId(),activeTask==null?null:activeTask.getStatus(),
                activeTask==null?null:activeTask.getTaskType(), summary,
                chapter.getApprovedTime(), chapter.getCreatedTime(), chapter.getUpdatedTime());
    }
    private ChapterVersionResponse currentVersionResponse(StoryChapterVersion current) {
        ChapterVersionResponse response = versionService.toResponse(current);
        if (current == null || current.getAiTaskId() == null) return response;
        List<String> rows = jdbc.queryForList("""
                SELECT data_json FROM ai_task_event
                WHERE task_id=? AND event_type='HUMAN_REVIEW_REQUIRED'
                ORDER BY sequence_no DESC, id DESC LIMIT 1
                """, String.class, current.getAiTaskId());
        if (rows.isEmpty()) return response;
        JsonNode data = support.read(rows.get(0));
        JsonNode mechanicalErrors = data == null ? null : data.get("mechanicalErrors");
        if (mechanicalErrors == null || !mechanicalErrors.isArray()) return response;
        ObjectNode review = response.review() != null && response.review().isObject()
                ? ((ObjectNode) response.review()).deepCopy()
                : support.mapper().createObjectNode();
        review.set("mechanicalErrors", mechanicalErrors.deepCopy());
        return new ChapterVersionResponse(response.id(), response.chapterId(), response.versionNo(),
                response.sourceType(), response.content(), response.contentHash(), response.baseVersionId(),
                response.aiTaskId(), response.promptVersion(), response.modelName(), review,
                response.changeSummary(), response.createdBy(), response.createdTime());
    }
    private JsonNode jsonArray(String value){
        JsonNode parsed=support.read(value);return parsed!=null&&parsed.isArray()?parsed:support.mapper().createArrayNode();
    }
    public RewriteProposalResponse proposalResponse(RewriteProposal p) {
        return new RewriteProposalResponse(p.getId(), p.getChapterId(), p.getBaseVersionId(), p.getAiTaskId(),
                p.getGenerationNo(), p.getStartOffset(), p.getEndOffset(), p.getSelectedText(), p.getSelectedTextHash(),
                p.getActionType(), p.getCustomInstruction(), p.getReplacementText(), p.getReplacementHash(), p.getReason(),
                p.getStatus(), p.getResolvedVersionId(), p.getCreatedTime(), p.getResolvedTime());
    }
    private void chapterNotFound() { throw new ApiException(HttpStatus.NOT_FOUND, "CHAPTER_NOT_FOUND", "章节不存在"); }
    private void proposalNotFound() { throw new ApiException(HttpStatus.NOT_FOUND, "REWRITE_PROPOSAL_NOT_FOUND", "AI 改写建议不存在"); }
}
