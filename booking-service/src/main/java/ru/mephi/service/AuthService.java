package ru.mephi.service;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.dto.AuthResponse;
import ru.mephi.dto.LoginRequest;
import ru.mephi.dto.RegisterRequest;
import ru.mephi.dto.UserResponse;
import ru.mephi.exception.UsernameAlreadyExistsException;
import ru.mephi.model.Role;
import ru.mephi.model.User;
import ru.mephi.repository.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class AuthService {

    private static final long TOKEN_TTL_SECONDS = 3600L;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
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

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        // Ищем пользователя. Не нашли — то же сообщение, что и при неверном пароле
        User user = userRepository.findByUsername(request.username())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        // Проверяем пароль через BCrypt
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        // Выпускаем JWT
        String token = jwtService.generateToken(user);

        return new AuthResponse(token, "Bearer", TOKEN_TTL_SECONDS);
    }
}
