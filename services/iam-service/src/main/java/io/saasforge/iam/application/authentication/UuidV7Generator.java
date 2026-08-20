package io.saasforge.iam.application.authentication;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** 生成规范 UUIDv7；数据库外需要先生成的 jti 与事件 ID 使用同一实现。 */
public final class UuidV7Generator {
    private final Clock clock;
    private final SecureRandom random;

    public UuidV7Generator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public UUID next() {
        long timestamp = clock.millis() & 0x0000ffffffffffffL;
        long mostSignificant = (timestamp << 16) | 0x7000L | random.nextInt(1 << 12);
        long leastSignificant = (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
