package io.saasforge.iam.application.authentication;

public final class SessionSlotAlreadyActiveException extends RuntimeException {
    public static final String CODE = "SESSION_SLOT_ALREADY_ACTIVE";

    public SessionSlotAlreadyActiveException() {
        super("所选 Browser Session Slot 已存在活动会话");
    }
}
