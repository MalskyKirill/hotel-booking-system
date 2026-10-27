package ru.mephi.exception;

public class HotelNotFoundException extends RuntimeException{
    private Long id;
    public HotelNotFoundException(Long id) {
        super("Hotel with id " + id + " not found");
    }

    public Long getId() {
        return id;
    }
}
