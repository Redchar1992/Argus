package com.storyforge.common.exception;

import java.time.Instant;

public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path
) {

    public static ApiError of(int status, String error, String code, String message, String path) {
        return new ApiError(Instant.now(), status, error, code, message, path);
    }
}
