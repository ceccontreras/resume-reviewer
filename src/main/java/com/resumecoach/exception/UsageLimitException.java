package com.resumecoach.exception;

public class UsageLimitException extends RuntimeException {
    public UsageLimitException(String message) {
        super(message);
    }
}
