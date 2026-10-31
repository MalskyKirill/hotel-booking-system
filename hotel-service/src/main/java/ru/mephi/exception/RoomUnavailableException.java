package ru.mephi.exception;

public class RoomUnavailableException extends RuntimeException {
    public RoomUnavailableException(Long roomId) {
        super("Room with id " + roomId + " is currently unavailable for booking");
    }
}
