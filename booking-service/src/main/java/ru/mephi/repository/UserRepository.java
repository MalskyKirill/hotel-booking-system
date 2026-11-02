package ru.mephi.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.mephi.model.Role;
import ru.mephi.model.User;

public interface UserRepository extends JpaRepository<User, Long> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM User u WHERE u.role = ru.mephi.model.Role.ADMIN ORDER BY u.id")
  List<User> lockAdministrators();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT u FROM User u WHERE u.id = :id")
  Optional<User> findByIdForUpdate(@Param("id") Long id);

  Optional<User> findByUsername(String username);

  boolean existsByUsername(String username);

  boolean existsByUsernameAndRole(String username, Role role);

  long countByRole(Role role);
}
