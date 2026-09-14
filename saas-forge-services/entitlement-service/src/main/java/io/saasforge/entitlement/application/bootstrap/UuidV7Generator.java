package io.saasforge.entitlement.application.bootstrap;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;

public final class UuidV7Generator {
    private final Clock clock;
    private final SecureRandom random;

    public UuidV7Generator(Clock clock, SecureRandom random) {
        this.clock = clock;
        this.random = random;
    }

    public UUID next() {
        long timestamp = clock.millis() & 0x0000FFFFFFFFFFFFL;
        long most = (timestamp << 16) | 0x7000L | random.nextInt(0x1000);
        long least = 0x8000000000000000L | (random.nextLong() & 0x3FFFFFFFFFFFFFFFL);
        return new UUID(most, least);
    }
}
