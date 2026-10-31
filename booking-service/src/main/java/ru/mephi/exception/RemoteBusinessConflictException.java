package ru.mephi.exception;

public class RemoteBusinessConflictException extends RuntimeException {
    private final String code;

    public RemoteBusinessConflictException(String message) {
        this("REMOTE_CONFLICT", message);
    }

    public RemoteBusinessConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
