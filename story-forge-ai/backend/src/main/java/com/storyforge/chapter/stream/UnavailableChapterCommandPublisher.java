package com.storyforge.chapter.stream;

import java.util.Map;
import com.storyforge.task.producer.WorkflowDispatchException;

public class UnavailableChapterCommandPublisher implements ChapterCommandPublisher {
    @Override public String publish(Map<String, String> fields) {
        throw new WorkflowDispatchException("章节工作流 Redis Stream 未启用");
    }
}
