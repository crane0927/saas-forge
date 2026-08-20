package io.saasforge.iam.domain.identity;

import java.time.Instant;
import java.util.UUID;

/** IAM 拥有的全局自然人或机器身份。 */
public final class Identity {

    private final UUID id;
    private final NormalizedEmail email;
    private final String displayName;
    private final Instant createdAt;

    private Identity(UUID id, NormalizedEmail email, String displayName, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.displayName = validateDisplayName(displayName);
        this.createdAt = requireTimestamp(createdAt, "创建时间不能为空");
    }

    public static Identity register(String email, String displayName, Instant createdAt) {
        return new Identity(null, NormalizedEmail.from(email), displayName, createdAt);
    }

    public static Identity restore(UUID id, NormalizedEmail email, String displayName, Instant createdAt) {
        if (id == null) {
            throw new IllegalArgumentException("Identity ID 不能为空");
        }
        return new Identity(id, email, displayName, createdAt);
    }

    public Identity identifiedBy(UUID generatedId) {
        if (generatedId == null) {
            throw new IllegalArgumentException("Identity ID 不能为空");
        }
        if (id != null) {
            throw new IllegalStateException("已持久化的 Identity 不能重新分配 ID");
        }
        return new Identity(generatedId, email, displayName, createdAt);
    }

    public UUID id() {
        return id;
    }

    public NormalizedEmail email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String validateDisplayName(String value) {
        if (value == null) {
            return null;
        }
        if (value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("显示名必须为 1 到 200 个字符");
        }
        return value;
    }

    private static Instant requireTimestamp(Instant value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
