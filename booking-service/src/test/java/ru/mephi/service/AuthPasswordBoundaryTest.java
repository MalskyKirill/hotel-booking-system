package ru.mephi.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.mephi.dto.LoginRequest;
import ru.mephi.repository.UserRepository;
import ru.mephi.model.*;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthPasswordBoundaryTest {
    final UserRepository users = mock(UserRepository.class);
    final PasswordEncoder encoder = mock(PasswordEncoder.class);
    final JwtService jwt = mock(JwtService.class);
    final AuthService service = new AuthService(users, encoder, jwt);
    @Test void overlongLoginPasswordIsRejectedWithoutCallingBcrypt() {
        var error = assertThrows(BadCredentialsException.class, () -> service.login(new LoginRequest("alice", "я".repeat(40))));
        assertEquals("Invalid credentials", error.getMessage());
        verifyNoInteractions(users, encoder, jwt);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void unknownUserAndWrongPasswordHaveSameError(boolean exists) {
        when(users.findByUsername("alice")).thenReturn(exists
            ? Optional.of(new User(1L, "alice", "encoded", Role.USER)) : Optional.empty());
        when(encoder.matches("wrong", "encoded")).thenReturn(false);
        var error = assertThrows(BadCredentialsException.class, () -> service.login(new LoginRequest("alice", "wrong")));
        assertEquals("Invalid credentials", error.getMessage());
        verifyNoInteractions(jwt);
    }
}
