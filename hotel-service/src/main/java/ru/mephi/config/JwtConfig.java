package ru.mephi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;

import java.security.interfaces.RSAPublicKey;

@Configuration
public class JwtConfig {

    /**
     * Публичный декодер: проверяет подпись, issuer, exp.
     * НЕ требует aud — пользовательские токены выпускаются без него.
     */
    @Bean("publicJwtDecoder")
    public JwtDecoder publicJwtDecoder(RSAPublicKey publicKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("hotel-booking-system"));
        return decoder;
    }

    /**
     * Внутренний декодер: проверяет подпись, issuer, exp,
     * aud=hotel-service и sub=booking-service.
     * Используется ТОЛЬКО для /internal/**.
     */
    @Bean("internalJwtDecoder")
    public JwtDecoder internalJwtDecoder(RSAPublicKey publicKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();

        OAuth2TokenValidator<Jwt> issuerAndTime =
            JwtValidators.createDefaultWithIssuer("hotel-booking-system");

        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
            if (jwt.getAudience() != null && jwt.getAudience().contains("hotel-service")) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token",
                    "Required audience 'hotel-service' not present", null));
        };

        OAuth2TokenValidator<Jwt> subjectValidator = jwt -> {
            if ("booking-service".equals(jwt.getSubject())) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token",
                    "Required subject 'booking-service' not present", null));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            issuerAndTime, audienceValidator, subjectValidator));
        return decoder;
    }
}
