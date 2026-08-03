package com.storyforge.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 50, message = "用户名长度应为 3-50 个字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 6, max = 72, message = "密码长度应为 6-72 个字符")
        String password,

        @NotNull(message = "注册前必须确认隐私说明")
        @AssertTrue(message = "注册前必须同意隐私说明")
        Boolean privacyAccepted
) {
}
