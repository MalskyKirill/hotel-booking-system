package ru.mephi.service;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
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

    public String generateToken(User user) {
        Instant now = Instant.now();

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer("hotel-booking-system")
            .subject(user.getUsername())
            .issuedAt(now)
            .expiresAt(now.plus(1, ChronoUnit.HOURS))
            .claim("role", user.getRole().name()) // USER или ADMIN
            .build();

        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).build();

        JwtEncoderParameters parameters = JwtEncoderParameters.from(jwsHeader, claims);
        return jwtEncoder.encode(parameters).getTokenValue();
    }
}
