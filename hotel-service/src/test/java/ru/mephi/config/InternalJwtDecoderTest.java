package ru.mephi.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InternalJwtDecoderTest {

    private static KeyPair keyPair;
    private static JwtDecoder decoder;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        decoder = new JwtConfig().internalJwtDecoder((RSAPublicKey) keyPair.getPublic());
    }

    @Test
    void acceptsBookingServiceToken() {
        String token = token("booking-service", "hotel-service", "hotel-booking-system",
            Instant.now().plusSeconds(300), keyPair);

        assertEquals("booking-service", decoder.decode(token).getSubject());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"another-service", " "})
    void rejectsMissingOrUnexpectedSubject(String subject) {
        String token = token(subject, "hotel-service", "hotel-booking-system",
            Instant.now().plusSeconds(300), keyPair);

        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsUnexpectedAudience() {
        String token = token("booking-service", "another-service", "hotel-booking-system",
            Instant.now().plusSeconds(300), keyPair);

        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsUnexpectedIssuer() {
        String token = token("booking-service", "hotel-service", "another-issuer",
            Instant.now().plusSeconds(300), keyPair);

        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsExpiredToken() {
        String token = token("booking-service", "hotel-service", "hotel-booking-system",
            Instant.now().minusSeconds(300), keyPair);

        assertThrows(JwtValidationException.class, () -> decoder.decode(token));
    }

    @Test
    void rejectsSignatureFromAnotherKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String token = token("booking-service", "hotel-service", "hotel-booking-system",
            Instant.now().plusSeconds(300), generator.generateKeyPair());

        assertThrows(JwtException.class, () -> decoder.decode(token));
    }

    @Test
    void publicDecoderStillAcceptsUserSubjectWithoutAudience() {
        JwtDecoder publicDecoder = new JwtConfig().publicJwtDecoder((RSAPublicKey) keyPair.getPublic());
        String token = token("alice", null, "hotel-booking-system",
            Instant.now().plusSeconds(300), keyPair);

        assertEquals("alice", publicDecoder.decode(token).getSubject());
    }

    private static String token(String subject, String audience, String issuer,
                                Instant expiresAt, KeyPair signingKey) {
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) signingKey.getPublic())
            .privateKey((RSAPrivateKey) signingKey.getPrivate()).build();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .issuedAt(Instant.now().minusSeconds(600))
            .expiresAt(expiresAt)
            .claim("role", "SERVICE");
        if (subject != null) {
            claims.subject(subject);
        }
        if (audience != null) {
            claims.audience(List.of(audience));
        }
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build())).getTokenValue();
    }
}
