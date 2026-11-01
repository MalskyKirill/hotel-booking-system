package ru.mephi.dto;

import java.util.List;

public record BookingPage(List<BookingResponse> content, int page, int size, long totalElements, int totalPages) { }
