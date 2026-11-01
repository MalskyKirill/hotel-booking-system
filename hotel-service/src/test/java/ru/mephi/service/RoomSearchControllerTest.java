package ru.mephi.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.mephi.controller.RoomController;
import java.time.LocalDate;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RoomSearchControllerTest {
    RoomService service;
    MockMvc mvc;
    @BeforeEach void setUp() {
        service = mock(RoomService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RoomController(service)).build();
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/rooms", "/api/rooms/recommend"})
    void datesAreRequired(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("startDate", "2027-01-01")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"/api/rooms", "/api/rooms/recommend"})
    void forwardsScopeAndDates(String path) throws Exception {
        var start = LocalDate.of(2027, 1, 1); var end = start.plusDays(2);
        when(service.search(1L, start, end, path.endsWith("recommend"))).thenReturn(List.of());
        mvc.perform(get(path).param("hotelId", "1").param("startDate", start.toString()).param("endDate", end.toString()))
            .andExpect(status().isOk());
        verify(service).search(1L, start, end, path.endsWith("recommend"));
    }
}
