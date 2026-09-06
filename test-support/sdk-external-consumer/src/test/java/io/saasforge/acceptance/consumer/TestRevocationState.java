package io.saasforge.acceptance.consumer;

import java.util.concurrent.atomic.AtomicBoolean;

final class TestRevocationState {

    private final AtomicBoolean unavailable = new AtomicBoolean();

    void setUnavailable(boolean unavailable) {
        this.unavailable.set(unavailable);
    }

    boolean isUnavailable() {
        return unavailable.get();
    }
}
