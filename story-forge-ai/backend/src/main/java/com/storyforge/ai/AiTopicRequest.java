package com.storyforge.ai;

public record AiTopicRequest(Long storyId, String genre, String audience, String keywords,
        String contentMode, String promptVersion, String promptSystem) {
    public AiTopicRequest(Long storyId, String genre, String audience, String keywords) {
        this(storyId, genre, audience, keywords, "SHORT_STORY", null, null);
    }
}
