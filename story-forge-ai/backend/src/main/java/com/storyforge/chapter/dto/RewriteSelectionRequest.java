package com.storyforge.chapter.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RewriteSelectionRequest(
        @NotNull Long chapterVersionId,
        @NotNull @Min(0) Integer startOffset,
        @NotNull @Min(1) Integer endOffset,
        @NotBlank @Size(max = 20000) String selectedText,
        @NotBlank @Pattern(regexp = "[a-fA-F0-9]{64}") String selectedTextHash,
        @NotBlank @Size(max = 40) String action,
        @Size(max = 1000) String customInstruction
) { }
