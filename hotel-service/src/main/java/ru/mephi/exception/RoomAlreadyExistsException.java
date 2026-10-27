package ru.mephi.exception;

public class RoomAlreadyExistsException extends RuntimeException {
    private Long hotelId;
    private String roomNumber;
    public RoomAlreadyExistsException(Long hotelId, String roomNumber) {
        super("Room " + roomNumber + " already exists in hotel " + hotelId);
        this.hotelId = hotelId;
        this.roomNumber = roomNumber;
    }

    public Long getHotelId() {
        return hotelId;
    }

    public String getRoomNumber() {
        return roomNumber;
    }
}
