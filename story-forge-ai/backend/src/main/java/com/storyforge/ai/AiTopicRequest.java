package com.storyforge.ai;

public record AiTopicRequest(Long storyId, String genre, String audience, String keywords,
        String promptVersion, String promptSystem) {
    public AiTopicRequest(Long storyId, String genre, String audience, String keywords) {
        this(storyId, genre, audience, keywords, null, null);
    }
}
