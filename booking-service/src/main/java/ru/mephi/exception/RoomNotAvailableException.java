package ru.mephi.exception;

public class RoomNotAvailableException extends RuntimeException {
    private final Long roomId;

    public RoomNotAvailableException(Long roomId, String message) {
        super("Room " + roomId + " is not available: " + message);
        this.roomId = roomId;
    }

    public Long getRoomId() {
        return roomId;
    }
}
