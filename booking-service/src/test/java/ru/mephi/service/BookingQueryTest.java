package ru.mephi.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import ru.mephi.dto.CreateBookingRequest;
import ru.mephi.exception.BookingNotFoundException;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({BookingQueryService.class, BookingCreationTransactions.class})
@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
class BookingQueryTest {
    @Autowired BookingQueryService queries;
    @Autowired BookingCreationTransactions transactions;
    @Autowired BookingRepository bookings;
    @Autowired UserRepository users;
    @BeforeEach void setUp() {
        bookings.deleteAll(); users.deleteAll();
        users.save(new User(null, "alice", "test", Role.USER));
        users.save(new User(null, "bob", "test", Role.USER));
    }
    @Test void onlyOwnerCanReadBooking() {
        var booking = create("alice", "key");
        assertEquals(booking, queries.get(booking.id(), "alice"));
        assertThrows(BookingNotFoundException.class, () -> queries.get(booking.id(), "bob"));
        assertThrows(BookingNotFoundException.class, () -> queries.get(-1L, "alice"));
    }
    @Test void historyIsOwnerFilteredAndStablyPaginated() {
        var first = create("alice", "a");
        var second = create("alice", "b");
        create("bob", "c");
        for (var booking : bookings.findAll()) {
            booking.setCreatedAt(Instant.EPOCH); bookings.save(booking);
        }
        var page = queries.history("alice", 0, 1);
        assertEquals(2, page.totalElements()); assertEquals(2, page.totalPages());
        assertEquals(second.id(), page.content().getFirst().id());
        assertEquals(first.id(), queries.history("alice", 1, 1).content().getFirst().id());
        assertTrue(queries.history("alice", 2, 1).content().isEmpty());
    }
    @ParameterizedTest @CsvSource({"-1,20", "0,0", "0,101"})
    void rejectsInvalidPageBounds(int page, int size) {
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> queries.history("alice", page, size));
    }
    private ru.mephi.dto.BookingResponse create(String user, String key) {
        return transactions.create(new CreateBookingRequest(1L, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3), key), user);
    }
}
