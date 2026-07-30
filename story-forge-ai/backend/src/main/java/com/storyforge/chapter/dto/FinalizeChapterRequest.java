package com.storyforge.chapter.dto;

import jakarta.validation.constraints.Size;

public record FinalizeChapterRequest(Boolean approved, @Size(max = 2000) String notes) {
    public boolean isApproved() { return approved == null || approved; }
}
