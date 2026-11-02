package ru.mephi.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.AutoReservationRequest;
import ru.mephi.dto.CreateReservationRequest;
import ru.mephi.model.Hotel;
import ru.mephi.model.ReservationStatus;
import ru.mephi.model.Room;
import ru.mephi.repository.*;

@DataJpaTest(showSql = false, properties = "hotel.auto-selection.candidate-limit=2")
@Import({
  AutoReservationService.class,
  AutoReservationTransactions.class,
  RoomReservationTransactions.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutoReservationContentionTest {
  @Autowired AutoReservationService service;
  @Autowired RoomReservationTransactions manual;
  @Autowired RoomReservationRepository reservations;
  @Autowired AutoReservationOperationRepository operations;
  @Autowired RoomRepository rooms;
  @Autowired HotelRepository hotels;
  @MockitoSpyBean AutoReservationTransactions transactions;
  private final List<Long> roomIds = new ArrayList<>();
  private Long hotelId;

  @BeforeEach
  void setUp() {
    reservations.deleteAll();
    operations.deleteAll();
    rooms.deleteAll();
    hotels.deleteAll();
    roomIds.clear();
    Hotel hotel = new Hotel();
    hotel.setName("Contention");
    hotel.setAddress("Local");
    hotelId = hotels.save(hotel).getId();
    for (int i = 0; i < 3; i++) {
      Room room = new Room();
      room.setHotel(hotel);
      room.setRoomNumber("C" + i);
      room.setAvailable(true);
      room.setCapacity(2);
      room.setPrice(BigDecimal.TEN);
      roomIds.add(rooms.save(room).getId());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 7})
  void findsRoomBeyondBatchWhenCandidatesBecomeOccupiedAfterSearch(int thirdRoomBookings) {
    Room third = rooms.findById(roomIds.getLast()).orElseThrow();
    third.setTimesBooked(thirdRoomBookings);
    rooms.save(third);
    var request =
        new AutoReservationRequest(1L, hotelId, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3));

    // Commit competing reservations after the candidate query but before its first lock.
    // Only this timing boundary is controlled; repository queries, locks and writes remain real.
    doAnswer(
            invocation -> {
              for (int i = 0; i < 2; i++) {
                manual.reserve(
                    new CreateReservationRequest(
                        100L + i, roomIds.get(i), request.startDate(), request.endDate()));
              }
              return invocation.callRealMethod();
            })
        .when(transactions)
        .attempt(request, roomIds.getFirst());

    var result = service.reserve(request);

    assertEquals(roomIds.getLast(), result.roomId());
    assertEquals(ReservationStatus.HELD, result.status());
    assertEquals(3, reservations.count());
    assertEquals(result, service.reserve(request));
    service.confirm(request);
    service.confirm(request);
    assertEquals(
        thirdRoomBookings + 1, rooms.findById(roomIds.getLast()).orElseThrow().getTimesBooked());
  }
}
