package com.storyforge.task.producer;

import java.util.Map;

public class UnavailableWorkflowRequestPublisher implements WorkflowRequestPublisher {

    @Override
    public String publish(Map<String, String> fields) {
        throw new WorkflowDispatchException("工作流 Redis Stream 未启用");
    }
}
