package ru.mephi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.mephi.model.*;
import ru.mephi.repository.UserRepository;
import java.nio.charset.StandardCharsets;

@Component
@Profile("demo")
public class DemoUserData implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminName, adminPassword, userName, userPassword;
    public DemoUserData(UserRepository users, PasswordEncoder encoder,
        @Value("${demo.admin.username:demo-admin}") String adminName,
        @Value("${demo.admin.password:}") String adminPassword,
        @Value("${demo.user.username:demo-user}") String userName,
        @Value("${demo.user.password:}") String userPassword) {
        this.users = users; this.encoder = encoder; this.adminName = adminName.trim(); this.adminPassword = adminPassword;
        this.userName = userName.trim(); this.userPassword = userPassword;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (adminName.isBlank() || userName.isBlank() || adminName.equals(userName)
            || adminName.length() > 100 || userName.length() > 100) {
            throw new IllegalArgumentException("Demo usernames must be distinct and nonblank (max 100 characters)");
        }
        validate(adminPassword); validate(userPassword);
        ensure(adminName, adminPassword, Role.ADMIN);
        ensure(userName, userPassword, Role.USER);
    }
    private void validate(String password) {
        if (password.isBlank() || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("Set DEMO_ADMIN_PASSWORD and DEMO_USER_PASSWORD: at least 8 characters, at most 72 UTF-8 bytes");
        }
    }
    private void ensure(String username, String password, Role role) {
        var previous = users.findByUsername(username);
        if (previous.isPresent()) {
            if (previous.get().getRole() != role) throw new IllegalStateException("Existing demo username has unexpected role");
            return;
        }
        var user = new User(); user.setUsername(username); user.setRole(role); user.setPassword(encoder.encode(password));
        users.save(user);
    }
}
