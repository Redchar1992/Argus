package com.storyforge.ai;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GenerateTopicRequest(
        @NotNull(message = "storyId 不能为空")
        @Positive(message = "storyId 必须为正数")
        Long storyId,

        @Size(min = 2, max = 50, message = "题材长度应为 2-50 个字符")
        String genre,

        @Size(max = 50, message = "受众最多 50 个字符")
        String audience,

        @Size(max = 309, message = "关键词总长度最多 309 个字符")
        String keywords
) {
}
