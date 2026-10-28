package ru.mephi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.RegisterRequest;
import ru.mephi.dto.UserResponse;
import ru.mephi.exception.UsernameAlreadyExistsException;
import ru.mephi.model.Role;
import ru.mephi.model.User;
import ru.mephi.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String username = request.username();

        // Предварительная проверка — быстро отдаём 409 в 99% случаев
        if (userRepository.existsByUsername(username)) {
            throw new UsernameAlreadyExistsException(username);
        }

        // Создаём пользователя с хешированным паролем и фиксированной ролью USER
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(request.password()));  // ← BCrypt
        user.setRole(Role.USER);   // ← клиент не может выбрать роль

        // Сохраняем. Если параллельный поток успел раньше — поймаем UNIQUE violation
        User saved = userRepository.save(user);

        return new UserResponse(saved.getId(), saved.getUsername(), saved.getRole());
    }
}
