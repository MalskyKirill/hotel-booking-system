package ru.mephi.service;

import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.mephi.dto.*;
import ru.mephi.exception.UsernameAlreadyExistsException;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import java.util.List;

@Service
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true)
public class UserAdminService {
    private final UserRepository users;
    private final BookingRepository bookings;
    private final PasswordEncoder encoder;
    public UserAdminService(UserRepository users, BookingRepository bookings, PasswordEncoder encoder) {
        this.users = users; this.bookings = bookings; this.encoder = encoder;
    }
    private void requireCurrentAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !users.existsByUsernameAndRole(authentication.getName(), Role.ADMIN)) {
            throw new AccessDeniedException("Current administrator account required");
        }
    }
    private long lockAdmins() {
        users.lockAdministrators();
        requireCurrentAdmin();
        return users.countByRole(Role.ADMIN);
    }
    public UserPage list(int page, int size) {
        requireCurrentAdmin();
        if (page < 0 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page bounds");
        var result = users.findAll(PageRequest.of(page, size, Sort.by("id")));
        return new UserPage(result.map(this::map).getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }
    public UserResponse get(Long id) {
        requireCurrentAdmin(); return map(users.findById(id).orElseThrow(() -> missing()));
    }
    @Transactional
    public UserResponse create(CreateUserRequest request) {
        lockAdmins();
        if (users.existsByUsername(request.username())) throw new UsernameAlreadyExistsException(request.username());
        User user = new User(); user.setUsername(request.username()); user.setRole(request.role());
        user.setPassword(encoder.encode(request.password()));
        return map(users.save(user));
    }
    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        var admins = lockAdmins();
        User user = users.findByIdForUpdate(id).orElseThrow(() -> missing());
        if (user.getRole() == Role.ADMIN && request.role() == Role.USER && admins == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot demote the last administrator");
        }
        if (request.role() != null) user.setRole(request.role());
        if (request.password() != null) user.setPassword(encoder.encode(request.password()));
        return map(user);
    }
    @Transactional
    public void delete(Long id) {
        var admins = lockAdmins();
        User user = users.findByIdForUpdate(id).orElseThrow(() -> missing());
        if (user.getRole() == Role.ADMIN && admins == 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot delete the last administrator");
        }
        if (bookings.existsByUser_Id(id)) throw new ResponseStatusException(HttpStatus.CONFLICT, "User has booking history");
        users.delete(user);
    }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"); }
    private UserResponse map(User user) { return new UserResponse(user.getId(), user.getUsername(), user.getRole()); }
}
