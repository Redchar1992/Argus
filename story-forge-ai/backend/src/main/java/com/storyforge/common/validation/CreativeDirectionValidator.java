package com.storyforge.common.validation;

import java.util.Arrays;
import java.util.List;

import com.storyforge.common.exception.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Keeps Spring's accepted creative direction inside the FastAPI/Pydantic
 * contract so invalid user input stays a 400 instead of becoming an upstream
 * 502.
 */
public final class CreativeDirectionValidator {

    private static final int MAX_KEYWORDS = 10;
    private static final int MAX_KEYWORD_LENGTH = 30;

    private CreativeDirectionValidator() {
    }

    public static String genre(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || normalized.length() < 2) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_GENRE",
                    "题材至少需要 2 个字符"
            );
        }
        return normalized;
    }

    public static String audience(String value, boolean required) {
        String normalized = trimToNull(value);
        if (required && normalized == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "AUDIENCE_REQUIRED",
                    "目标受众不能为空"
            );
        }
        if (normalized != null && normalized.length() > 50) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AUDIENCE",
                    "目标受众最多 50 个字符"
            );
        }
        return normalized;
    }

    public static String keywords(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return null;
        }

        List<String> parts = Arrays.stream(normalized.split("[,，、;；\\s]+"))
                .filter(StringUtils::hasText)
                .toList();
        if (parts.size() > MAX_KEYWORDS) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "TOO_MANY_KEYWORDS",
                    "关键词最多 10 个"
            );
        }
        if (parts.stream().anyMatch(part -> part.length() > MAX_KEYWORD_LENGTH)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "KEYWORD_TOO_LONG",
                    "每个关键词最多 30 个字符"
            );
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
