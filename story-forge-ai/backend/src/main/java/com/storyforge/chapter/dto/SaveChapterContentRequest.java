package com.storyforge.chapter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.storyforge.chapter.ChapterContentPolicy;

public record SaveChapterContentRequest(
        @NotNull Long baseVersionId,
        @NotBlank @Size(max = ChapterContentPolicy.MAX_LENGTH) String content,
        String baseContentHash
) { }
