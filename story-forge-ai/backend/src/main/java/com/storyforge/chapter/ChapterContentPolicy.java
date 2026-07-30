package com.storyforge.chapter;

import com.storyforge.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public final class ChapterContentPolicy {
    public static final int MAX_LENGTH=100_000;

    public static void requireValidLength(String content) {
        if (content != null && content.length() > MAX_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CHAPTER_CONTENT_TOO_LONG",
                    "章节正文不能超过 " + MAX_LENGTH + " 个字符"
            );
        }
    }

    private ChapterContentPolicy() { }
}
