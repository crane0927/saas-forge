package io.saasforge.iambootstrap;

import java.security.SecureRandom;
import java.util.HexFormat;

final class TraceIdGenerator {
    private final SecureRandom secureRandom;

    TraceIdGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    String next() {
        byte[] traceId = new byte[16];
        do {
            secureRandom.nextBytes(traceId);
        } while (allZero(traceId));
        return HexFormat.of().formatHex(traceId);
    }

    private static boolean allZero(byte[] value) {
        for (byte current : value) {
            if (current != 0) {
                return false;
            }
        }
        return true;
    }
}
