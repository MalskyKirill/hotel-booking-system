package ru.mephi.dto;

import java.math.BigDecimal;

public class RoomResponse {
    private Long id;
    private Long hotelId;
    private String roomNumber;
    private Integer capacity;
    private BigDecimal price;
    private Boolean available;
    private Integer timesBooked;

    public RoomResponse(Long id, Long hotelId, String roomNumber, Integer capacity, BigDecimal price, Boolean available, Integer timesBooked) {
        this.id = id;
        this.hotelId = hotelId;
        this.roomNumber = roomNumber;
        this.capacity = capacity;
        this.price = price;
        this.available = available;
        this.timesBooked = timesBooked;
    }

    public RoomResponse() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getHotelId() {
        return hotelId;
    }

    public void setHotelId(Long hotelId) {
        this.hotelId = hotelId;
    }

    public String getRoomNumber() {
        return roomNumber;
    }

    public void setRoomNumber(String roomNumber) {
        this.roomNumber = roomNumber;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public void setCapacity(Integer capacity) {
        this.capacity = capacity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public Boolean getAvailable() {
        return available;
    }

    public void setAvailable(Boolean available) {
        this.available = available;
    }

    public Integer getTimesBooked() {
        return timesBooked;
    }

    public void setTimesBooked(Integer timesBooked) {
        this.timesBooked = timesBooked;
    }
}
