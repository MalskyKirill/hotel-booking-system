package ru.mephi.exception;

public class NoAvailableRoomException extends RuntimeException {
    public NoAvailableRoomException() { super("No available room found within the candidate limit"); }
}
