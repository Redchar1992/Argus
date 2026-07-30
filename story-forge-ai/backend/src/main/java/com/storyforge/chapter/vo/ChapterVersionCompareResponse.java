package com.storyforge.chapter.vo;

import com.fasterxml.jackson.databind.JsonNode;

public record ChapterVersionCompareResponse(
        ChapterVersionResponse fromVersion,
        ChapterVersionResponse toVersion,
        int commonPrefixLength,
        int commonSuffixLength,
        String fromChangedText,
        String toChangedText,
        JsonNode changes
) { }
