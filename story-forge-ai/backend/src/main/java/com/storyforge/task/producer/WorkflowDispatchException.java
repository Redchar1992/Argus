package com.storyforge.task.producer;

public class WorkflowDispatchException extends RuntimeException {

    public WorkflowDispatchException(String message) {
        super(message);
    }

    public WorkflowDispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
