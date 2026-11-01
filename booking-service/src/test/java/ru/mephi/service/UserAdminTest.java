package ru.mephi.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.*;
import ru.mephi.dto.*;
import ru.mephi.model.*;
import ru.mephi.repository.*;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(showSql = false)
@Import({UserAdminService.class, BookingCreationTransactions.class, UserAdminTest.Security.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserAdminTest {
    @TestConfiguration @EnableMethodSecurity static class Security {
        @Bean PasswordEncoder encoder() { return new BCryptPasswordEncoder(); }
    }
    @Autowired UserAdminService admin;
    @Autowired BookingCreationTransactions transactions;
    @Autowired UserRepository users;
    @Autowired BookingRepository bookings;
    @Autowired PasswordEncoder encoder;
    Long adminId;
    @BeforeEach void setUp() {
        bookings.deleteAll(); users.deleteAll();
        adminId = users.save(new User(null, "admin", "unused-test-hash", Role.ADMIN)).getId();
        authenticate("admin", "ADMIN");
    }
    @AfterEach void cleanSecurity() { SecurityContextHolder.clearContext(); }
    @Test void createReadUpdateAndDeleteWithoutExposingPassword() {
        var created = admin.create(new CreateUserRequest(" alice ", "TestUser123!", Role.USER));
        assertEquals("alice", created.username()); assertEquals(Role.USER, created.role());
        var stored = users.findById(created.id()).orElseThrow();
        assertNotEquals("TestUser123!", stored.getPassword()); assertTrue(encoder.matches("TestUser123!", stored.getPassword()));
        assertEquals(created, admin.get(created.id())); assertEquals(2, admin.list(0, 20).totalElements());
        var updated = admin.update(created.id(), new UpdateUserRequest("UpdatedPass123!", Role.ADMIN));
        assertEquals(Role.ADMIN, updated.role());
        assertTrue(encoder.matches("UpdatedPass123!", users.findById(created.id()).orElseThrow().getPassword()));
        admin.delete(created.id()); assertFalse(users.existsById(created.id()));
    }
    @Test void usernameIsUniqueAndBookingHistoryPreventsDeletion() {
        var created = admin.create(new CreateUserRequest("alice", "TestUser123!", Role.USER));
        assertThrows(ru.mephi.exception.UsernameAlreadyExistsException.class,
            () -> admin.create(new CreateUserRequest("alice", "OtherPass123!", Role.ADMIN)));
        transactions.create(new CreateBookingRequest(1L, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 3), "history"), "alice");
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> admin.delete(created.id()));
        assertTrue(users.existsById(created.id()));
    }
    @Test void lastAdministratorCannotBeDeletedOrDemoted() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> admin.delete(adminId));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
            () -> admin.update(adminId, new UpdateUserRequest(null, Role.USER)));
        assertEquals(1, users.countByRole(Role.ADMIN));
    }
    @Test void oldAdminAuthorityDoesNotBypassCurrentDatabaseRole() {
        var actor = users.findById(adminId).orElseThrow(); actor.setRole(Role.USER); users.save(actor);
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> admin.list(0, 20));
        authenticate("admin", "USER");
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
            () -> admin.create(new CreateUserRequest("other", "TestUser123!", Role.USER)));
    }
    @Test void concurrentSelfDemotionsKeepOneAdministrator() throws Exception {
        Long other = users.save(new User(null, "other-admin", "unused-test-hash", Role.ADMIN)).getId();
        var barrier = new CyclicBarrier(2); var executor = Executors.newFixedThreadPool(2);
        try {
            var a = executor.submit(() -> demote(barrier, "admin", adminId));
            var b = executor.submit(() -> demote(barrier, "other-admin", other));
            int successes = (a.get(15, TimeUnit.SECONDS) ? 1 : 0) + (b.get(15, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, successes); assertEquals(1, users.countByRole(Role.ADMIN));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    private boolean demote(CyclicBarrier barrier, String name, Long id) throws Exception {
        authenticate(name, "ADMIN");
        try {
            barrier.await(5, TimeUnit.SECONDS); admin.update(id, new UpdateUserRequest(null, Role.USER)); return true;
        } catch (org.springframework.web.server.ResponseStatusException ex) { return false; }
        finally { SecurityContextHolder.clearContext(); }
    }
    static void authenticate(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "unused",
            List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
