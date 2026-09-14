package io.saasforge.iam.domain.identity;

import java.util.Locale;
import java.util.Objects;

/** 全局 Identity 使用的规范化 ASCII 邮箱地址。 */
public record NormalizedEmail(String value) {

    public NormalizedEmail {
        Objects.requireNonNull(value, "邮箱不能为空");
        if (value.isBlank() || !value.matches("^[\\x00-\\x7F]+$") || !value.matches("^[^@\\s]+@[^@\\s]+$")) {
            throw new IllegalArgumentException("邮箱必须是有效的 ASCII 地址");
        }
    }

    public static NormalizedEmail from(String email) {
        return new NormalizedEmail(Objects.requireNonNull(email, "邮箱不能为空").trim().toLowerCase(Locale.ROOT));
    }
}
