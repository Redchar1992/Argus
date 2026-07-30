package com.storyforge.workflow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewDecisionRequest(
        @NotNull(message = "approved 不能为空")
        Boolean approved,

        @Size(max = 2000, message = "修改意见最多 2000 个字符")
        String notes
) {
}
