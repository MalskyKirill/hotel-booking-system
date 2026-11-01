package ru.mephi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.mephi.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.role = ru.mephi.model.Role.ADMIN ORDER BY u.id")
    java.util.List<User> lockAdministrators();

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
    boolean existsByUsernameAndRole(String username, ru.mephi.model.Role role);
    long countByRole(ru.mephi.model.Role role);
}
