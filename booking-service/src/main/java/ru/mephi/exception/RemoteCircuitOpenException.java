package ru.mephi.exception;

/** No remote result exists; Saga must defer rather than claim successful compensation. */
public class RemoteCircuitOpenException extends RemoteServiceUncertainException {
    public RemoteCircuitOpenException(Throwable cause) { super("Hotel circuit is open", cause); }
}
