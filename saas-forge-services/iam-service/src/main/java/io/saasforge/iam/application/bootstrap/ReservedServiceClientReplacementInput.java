package io.saasforge.iam.application.bootstrap;

import java.util.UUID;

public record ReservedServiceClientReplacementInput(
        UUID replacementRequestId,
        ReservedServiceClient service,
        UUID oldClientId,
        UUID newClientId,
        String newClientSecret) {

    public ReservedServiceClientReplacementInput {
        requireUuidV7(replacementRequestId, "Replacement Request ID");
        requireUuidV7(oldClientId, "旧 Client ID");
        requireUuidV7(newClientId, "新 Client ID");
        if (service == null || oldClientId.equals(newClientId)) {
            throw new IllegalArgumentException("Reserved Service Client Replacement 输入不合法");
        }
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) throw new IllegalArgumentException(field + " 必须是 UUIDv7");
    }
}
