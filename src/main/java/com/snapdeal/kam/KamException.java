package com.snapdeal.kam;

/** Any failure we want to report to the browser as a readable error. */
public class KamException extends RuntimeException {
    public KamException(String message) {
        super(message);
    }

    public KamException(String message, Throwable cause) {
        super(message, cause);
    }
}
