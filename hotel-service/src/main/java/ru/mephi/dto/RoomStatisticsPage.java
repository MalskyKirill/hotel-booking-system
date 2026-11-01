package ru.mephi.dto;

import java.util.List;
public record RoomStatisticsPage(List<RoomStatistics> content, int page, int size, long totalElements, int totalPages) { }
