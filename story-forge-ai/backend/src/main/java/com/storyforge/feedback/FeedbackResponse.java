package com.storyforge.feedback;

import java.time.LocalDateTime;

public record FeedbackResponse(Long id, Long storyId, FeedbackRequest feedback, LocalDateTime createdTime) { }
