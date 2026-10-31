package ru.mephi.exception;

public class RemoteRoomNotFoundException extends RuntimeException {
    private final Long roomId;

    public RemoteRoomNotFoundException(Long roomId) {
        super("Room with id " + roomId + " not found in hotel service");
        this.roomId = roomId;
    }

    public Long getRoomId() {
        return roomId;
    }
}
