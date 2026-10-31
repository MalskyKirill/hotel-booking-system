package ru.mephi.dto;

public record BookingSagaSnapshot(BookingResponse booking, String requestId, boolean compensating) { }
