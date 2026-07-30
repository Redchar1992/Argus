package com.storyforge.story;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStoryRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 255, message = "标题最多 255 个字符")
        String title,

        @NotBlank(message = "题材不能为空")
        @Size(min = 2, max = 50, message = "题材长度应为 2-50 个字符")
        String genre,

        @Size(max = 50, message = "受众最多 50 个字符")
        String audience,

        @Size(max = 309, message = "关键词总长度最多 309 个字符")
        String keywords
) {
}
