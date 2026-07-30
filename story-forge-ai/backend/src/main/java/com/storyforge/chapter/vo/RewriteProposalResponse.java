package com.storyforge.chapter.vo;

import java.time.LocalDateTime;

public record RewriteProposalResponse(
        Long proposalId,
        Long chapterId,
        Long baseVersionId,
        Long aiTaskId,
        Integer generationNo,
        Integer startOffset,
        Integer endOffset,
        String originalText,
        String originalTextHash,
        String action,
        String customInstruction,
        String replacementText,
        String replacementTextHash,
        String reason,
        String status,
        Long resolvedVersionId,
        LocalDateTime createdTime,
        LocalDateTime resolvedTime
) { }
