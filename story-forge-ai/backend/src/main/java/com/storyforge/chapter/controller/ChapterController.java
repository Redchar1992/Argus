package com.storyforge.chapter.controller;

import java.util.List;
import com.storyforge.chapter.dto.AcceptProposalRequest;
import com.storyforge.chapter.dto.ApprovePlanRequest;
import com.storyforge.chapter.dto.FinalizeChapterRequest;
import com.storyforge.chapter.dto.PlanChapterRequest;
import com.storyforge.chapter.dto.RewriteSelectionRequest;
import com.storyforge.chapter.dto.SaveChapterContentRequest;
import com.storyforge.chapter.service.ChapterApplicationService;
import com.storyforge.chapter.vo.ChapterResponse;
import com.storyforge.chapter.vo.ChapterTaskResponse;
import com.storyforge.chapter.vo.ChapterVersionCompareResponse;
import com.storyforge.chapter.vo.ChapterVersionResponse;
import com.storyforge.chapter.vo.RewriteProposalResponse;
import com.storyforge.common.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api")
public class ChapterController {
    private final ChapterApplicationService service;
    public ChapterController(ChapterApplicationService service) { this.service = service; }

    @GetMapping("/stories/{storyId}/chapters")
    public List<ChapterResponse> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long storyId) {
        return service.list(user.userId(), storyId);
    }
    @GetMapping("/stories/{storyId}/chapters/{chapterNo}")
    public ChapterResponse byNo(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long storyId,
                                @PathVariable @Min(1) Integer chapterNo) {
        return service.getByNo(user.userId(), storyId, chapterNo);
    }
    @GetMapping("/stories/{storyId}/chapters/{chapterNo}/plan")
    public ChapterResponse planDetail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long storyId,
                                      @PathVariable @Min(1) Integer chapterNo) {
        return service.getByNo(user.userId(), storyId, chapterNo);
    }
    @PostMapping("/stories/{storyId}/chapters/{chapterNo}/plan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChapterTaskResponse plan(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long storyId,
                                    @PathVariable @Min(1) Integer chapterNo,
                                    @Valid @RequestBody(required = false) PlanChapterRequest request) {
        return service.plan(user.userId(), storyId, chapterNo, request);
    }
    @PostMapping("/stories/{storyId}/chapters/{chapterNo}/plan/approve")
    public ChapterResponse approvePlan(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long storyId,
                                       @PathVariable @Min(1) Integer chapterNo,
                                       @RequestBody(required = false) ApprovePlanRequest request) {
        return service.approvePlan(user.userId(), storyId, chapterNo, request);
    }
    @PostMapping("/stories/{storyId}/chapters/{chapterNo}/generate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChapterTaskResponse generate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long storyId,
                                        @PathVariable @Min(1) Integer chapterNo) {
        return service.generate(user.userId(), storyId, chapterNo);
    }

    @GetMapping("/chapters/{chapterId}")
    public ChapterResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long chapterId) {
        return service.get(user.userId(), chapterId);
    }
    @PutMapping("/chapters/{chapterId}/content")
    public ChapterVersionResponse save(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long chapterId,
                                       @Valid @RequestBody SaveChapterContentRequest request) {
        return service.saveContent(user.userId(), chapterId, request);
    }
    @PostMapping("/chapters/{chapterId}/rewrite-selection")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChapterTaskResponse rewrite(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long chapterId,
                                       @Valid @RequestBody RewriteSelectionRequest request) {
        return service.rewrite(user.userId(), chapterId, request);
    }
    @GetMapping("/chapters/{chapterId}/rewrite-proposals")
    public List<RewriteProposalResponse> proposals(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @PathVariable Long chapterId) {
        return service.proposals(user.userId(), chapterId);
    }
    @GetMapping("/chapters/{chapterId}/rewrite-proposals/{proposalId}")
    public RewriteProposalResponse proposal(@AuthenticationPrincipal AuthenticatedUser user,
                                             @PathVariable Long chapterId, @PathVariable Long proposalId) {
        return service.proposal(user.userId(), chapterId, proposalId);
    }
    @PostMapping("/chapters/{chapterId}/rewrite-proposals/{proposalId}/accept")
    public ChapterVersionResponse accept(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long chapterId,
                                          @PathVariable Long proposalId,
                                          @RequestBody(required = false) AcceptProposalRequest request) {
        return service.accept(user.userId(), chapterId, proposalId, request);
    }
    @PostMapping("/chapters/{chapterId}/rewrite-proposals/{proposalId}/reject")
    public RewriteProposalResponse reject(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long chapterId,
                                           @PathVariable Long proposalId) {
        return service.reject(user.userId(), chapterId, proposalId);
    }
    @PostMapping("/chapters/{chapterId}/rewrite-proposals/{proposalId}/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChapterTaskResponse regenerate(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable Long chapterId, @PathVariable Long proposalId) {
        return service.regenerate(user.userId(), chapterId, proposalId);
    }
    @GetMapping("/chapters/{chapterId}/versions")
    public List<ChapterVersionResponse> versions(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable Long chapterId) {
        return service.versions(user.userId(), chapterId);
    }
    @GetMapping("/chapters/{chapterId}/versions/compare")
    public ChapterVersionCompareResponse compare(@AuthenticationPrincipal AuthenticatedUser user,
                                                  @PathVariable Long chapterId,
                                                  @RequestParam Long fromVersionId,
                                                  @RequestParam Long toVersionId) {
        return service.compare(user.userId(), chapterId, fromVersionId, toVersionId);
    }
    @PostMapping("/chapters/{chapterId}/versions/{versionId}/restore")
    public ChapterVersionResponse restore(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable Long chapterId, @PathVariable Long versionId) {
        return service.restore(user.userId(), chapterId, versionId);
    }
    @PostMapping("/chapters/{chapterId}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChapterTaskResponse approve(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long chapterId,
                                       @Valid @RequestBody(required = false) FinalizeChapterRequest request) {
        return service.finalizeChapter(user.userId(), chapterId, request);
    }
}
