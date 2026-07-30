package com.storyforge.task.producer;

import java.util.Map;

public interface WorkflowRequestPublisher {

    String publish(Map<String, String> fields);
}
