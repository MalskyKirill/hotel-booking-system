package ru.mephi.dto;

import java.util.List;
public record RoomPage(List<RoomResponse> content, int page, int size, long totalElements, int totalPages) { }
