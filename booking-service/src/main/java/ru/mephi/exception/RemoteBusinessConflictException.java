package ru.mephi.exception;

public class RemoteBusinessConflictException extends RuntimeException {
    public RemoteBusinessConflictException(String message) {
        super(message);
    }
}
