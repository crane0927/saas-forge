package io.saasforge.tenantaccess.application.tenant;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

/** 为数据库事务开始前必须确定的 Tenant、事件和操作标识生成 UUIDv7。 */
public final class UuidV7Generator {
    private final Clock clock;
    private final SecureRandom random;

    public UuidV7Generator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public UUID next() {
        long timestamp = clock.millis() & 0x0000ffffffffffffL;
        long randomA = random.nextLong() & 0x0fffL;
        long randomB = random.nextLong() & 0x3fffffffffffffffL;
        long mostSignificant = (timestamp << 16) | 0x7000L | randomA;
        long leastSignificant = 0x8000000000000000L | randomB;
        return new UUID(mostSignificant, leastSignificant);
    }
}
