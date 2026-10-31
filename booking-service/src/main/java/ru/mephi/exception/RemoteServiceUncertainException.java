package ru.mephi.exception;

public class RemoteServiceUncertainException extends RuntimeException {
    public RemoteServiceUncertainException(String message) {
        super(message);
    }

    public RemoteServiceUncertainException(String message, Throwable cause) {
        super(message, cause);
    }
}
