package com.storyforge.feedback;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record FeedbackRequest(
        @Min(1) @Max(5) Integer topicScore,
        @Min(1) @Max(5) Integer characterScore,
        @Min(1) @Max(5) Integer outlineScore,
        @Min(1) @Max(5) Integer chapterScore,
        @Min(1) @Max(5) Integer reportScore,
        @Min(1) @Max(5) Integer exportScore,
        @Size(max = 64) String willingness,
        @Size(max = 100) String favoriteFeature,
        @Size(max = 4000) String biggestProblem
) { }
