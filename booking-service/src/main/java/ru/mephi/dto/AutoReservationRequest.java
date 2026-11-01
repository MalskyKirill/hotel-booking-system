package ru.mephi.dto;

import java.time.LocalDate;

public record AutoReservationRequest(Long bookingId, Long hotelId, LocalDate startDate, LocalDate endDate) { }
