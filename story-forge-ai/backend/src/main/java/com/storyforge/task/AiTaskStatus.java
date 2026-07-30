package com.storyforge.task;

public final class AiTaskStatus {

    public static final String WAITING = "WAITING";
    public static final String RUNNING = "RUNNING";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    public static boolean isWorkflowStatus(String status) {
        return WAITING.equals(status)
                || RUNNING.equals(status)
                || REVIEW_REQUIRED.equals(status)
                || SUCCESS.equals(status)
                || FAILED.equals(status);
    }

    /**
     * Keeps one workflow operation monotonic when events are delivered late or
     * processed by different stream consumers. Dispatch retries reset a failed
     * task explicitly before publishing; Redis events themselves may not reopen
     * a completed or human-review task. FAILED remains recoverable because a
     * claimed Redis delivery can retry the same operation.
     */
    public static boolean canTransition(String currentStatus, String nextStatus) {
        if (!isWorkflowStatus(nextStatus)) {
            return false;
        }
        if (!isWorkflowStatus(currentStatus) || currentStatus.equals(nextStatus)) {
            return true;
        }
        return switch (currentStatus) {
            case WAITING -> true;
            case RUNNING -> REVIEW_REQUIRED.equals(nextStatus)
                    || SUCCESS.equals(nextStatus)
                    || FAILED.equals(nextStatus);
            case REVIEW_REQUIRED -> SUCCESS.equals(nextStatus);
            case FAILED -> RUNNING.equals(nextStatus)
                    || REVIEW_REQUIRED.equals(nextStatus)
                    || SUCCESS.equals(nextStatus);
            case SUCCESS -> false;
            default -> false;
        };
    }

    private AiTaskStatus() {
    }
}
