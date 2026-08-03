package com.storyforge.story;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.JsonNode;

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
        String keywords,

        @Pattern(regexp = "(?i)^(SHORT_STORY|NOVEL|SHORT|LONG)$", message = "内容模式必须是 SHORT_STORY 或 NOVEL")
        String contentMode,

        @Min(value = 1, message = "目标章节数必须大于 0")
        @Max(value = 200, message = "目标章节数不能超过 200")
        Integer targetChapterCount,

        @Min(value = 1_000, message = "目标总字数不能低于 1000")
        @Max(value = 2_000_000, message = "目标总字数不能超过 2000000")
        Integer targetTotalWords,

        @Min(value = 800, message = "单章目标字数不能低于 800")
        @Max(value = 8_000, message = "单章目标字数不能超过 8000")
        Integer chapterTargetWords,

        @Size(max = 32, message = "叙事视角最多 32 个字符")
        String viewpoint,

        JsonNode styleProfile
) {
}
