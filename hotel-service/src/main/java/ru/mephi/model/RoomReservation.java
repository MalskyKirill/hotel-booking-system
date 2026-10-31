package ru.mephi.model;

import jakarta.persistence.*;

import java.time.LocalDate;

@Entity
@Table(
    name = "room_reservations",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_reservations_booking_id",
        columnNames = "booking_id"
    ),
    indexes = @Index(
        name = "idx_reservations_room_dates",
        columnList = "room_id, start_date, end_date, status"
    )
)
public class RoomReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /**
     * Идентификатор бронирования из Booking Service (другая БД).
     * Обычный Long, не JPA-связь — это граница между сервисами.
     */
    @Column(name = "booking_id", nullable = false, unique = true)
    private Long bookingId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    public RoomReservation() {
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Room getRoom() { return room; }
    public void setRoom(Room room) { this.room = room; }

    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public ReservationStatus getStatus() { return status; }
    public void setStatus(ReservationStatus status) { this.status = status; }
}
