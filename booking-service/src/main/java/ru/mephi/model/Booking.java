package ru.mephi.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(indexes = @Index(name = "idx_booking_saga_due", columnList = "status,saga_next_attempt_at,id"))
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "room_id")
    private Long roomId;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BookingStatus status;
    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "request_id", nullable = false, unique = true)
    private String requestId;

    @Column(nullable = false) private boolean cancellationRequested;
    public boolean isCancellationRequested() { return cancellationRequested; }
    public void setCancellationRequested(boolean value) { cancellationRequested = value; }

    @Column(nullable = false) private boolean autoSelect;
    private Long hotelId;
    public boolean isAutoSelect() { return autoSelect; }
    public void setAutoSelect(boolean value) { autoSelect = value; }
    public Long getHotelId() { return hotelId; }
    public void setHotelId(Long value) { hotelId = value; }

    @Column(nullable = false)
    private boolean sagaCompensating;
    @Column(nullable = false)
    private Instant sagaNextAttemptAt = Instant.now().plusSeconds(30);
    @Column(nullable = false)
    private int sagaRecoveryAttempts;

    public boolean isSagaCompensating() { return sagaCompensating; }
    public void setSagaCompensating(boolean value) { sagaCompensating = value; }
    public Instant getSagaNextAttemptAt() { return sagaNextAttemptAt; }
    public void setSagaNextAttemptAt(Instant value) { sagaNextAttemptAt = value; }
    public int getSagaRecoveryAttempts() { return sagaRecoveryAttempts; }
    public void setSagaRecoveryAttempts(int value) { sagaRecoveryAttempts = value; }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Booking() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public void setStatus(BookingStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
