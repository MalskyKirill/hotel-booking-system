package ru.mephi.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import ru.mephi.model.AutoReservationOperation;

public interface AutoReservationOperationRepository
    extends JpaRepository<AutoReservationOperation, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT o FROM AutoReservationOperation o WHERE o.bookingId = :id")
  Optional<AutoReservationOperation> lock(@Param("id") Long id);
}
