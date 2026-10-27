package ru.mephi.exception;

public class HotelHasRoomException extends RuntimeException{
    private Long id;
    public HotelHasRoomException (Long id) {
        super("Cannot delete hotel with id " + id + " because it has existing rooms");
        this.id = id;
    }

    public Long getId() {
        return id;
    }
}
