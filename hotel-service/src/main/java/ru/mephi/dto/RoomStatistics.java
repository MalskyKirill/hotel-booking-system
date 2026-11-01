package ru.mephi.dto;

public record RoomStatistics(Long roomId, Long hotelId, String roomNumber, boolean available, int timesBooked,
    long periodNights, long heldReservations, long confirmedReservations, long heldNights,
    long confirmedNights, double confirmedOccupancyPercent) { }
