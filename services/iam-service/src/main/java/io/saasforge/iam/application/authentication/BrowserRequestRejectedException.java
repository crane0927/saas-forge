package io.saasforge.iam.application.authentication;

public final class BrowserRequestRejectedException extends RuntimeException {
    public static final String CODE = "BROWSER_REQUEST_REJECTED";

    public BrowserRequestRejectedException() {
        super("Browser request security metadata was rejected");
    }
}
