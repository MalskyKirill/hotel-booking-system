package ru.mephi.service;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import ru.mephi.model.User;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;

    public JwtService(JwtEncoder jwtEncoder) {
        this.jwtEncoder = jwtEncoder;
    }

    // Пользовательский JWT (для клиентов)
    public String generateToken(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("hotel-booking-system")
            .subject(user.getUsername())
            .issuedAt(now)
            .expiresAt(now.plus(1, ChronoUnit.HOURS))
            .claim("role", user.getRole().name())
            .build();
        return encode(claims);
    }

    // Служебный JWT (для межсервисного взаимодействия)
    public String generateServiceToken() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("hotel-booking-system")
            .subject("booking-service")           // <-- кто выпустил
            .audience(java.util.List.of("hotel-service")) // <-- для кого
            .issuedAt(now)
            .expiresAt(now.plus(5, ChronoUnit.MINUTES))   // <-- короткоживущий
            .claim("role", "SERVICE")             // <-- специальное полномочие
            .build();
        return encode(claims);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
