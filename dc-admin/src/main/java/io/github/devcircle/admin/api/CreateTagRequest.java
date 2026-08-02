package io.github.devcircle.admin.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTagRequest(
        @NotBlank(message = "标签名称不能为空")
        @Size(max = 64, message = "标签名称不能超过 64 个字符")
        String name
) {
}
