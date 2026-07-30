package com.storyforge.chapter.stream;

import java.util.Map;

public interface ChapterCommandPublisher {
    String publish(Map<String, String> fields);
}
