package ru.mephi.exception;

public class RoomNotFoundException extends RuntimeException{
    private Long id;
    public RoomNotFoundException(Long id) {
        super("Room with id " + id + " not found");
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
