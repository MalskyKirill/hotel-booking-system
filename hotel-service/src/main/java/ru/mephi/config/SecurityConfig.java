package ru.mephi.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import ru.mephi.api.ApiSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * Цепочка №1: /internal/** — только для служебных токенов.
     * - Собственный JwtDecoder с проверкой aud=hotel-service.
     * - Требует ROLE_SERVICE.
     * - @Order(1) — срабатывает раньше публичной цепочки.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain internalChain(
        HttpSecurity http,
        @Qualifier("internalJwtDecoder") JwtDecoder internalJwtDecoder, ObjectMapper mapper) throws Exception {

        http
            .securityMatcher("/internal/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(ApiSecurityHandlers.unauthorized(mapper))
                .accessDeniedHandler(ApiSecurityHandlers.forbidden(mapper)))
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("SERVICE"))
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(ApiSecurityHandlers.unauthorized(mapper))
                .accessDeniedHandler(ApiSecurityHandlers.forbidden(mapper))
                .jwt(jwt -> jwt
                    .decoder(internalJwtDecoder)
                    .jwtAuthenticationConverter(new CustomJwtAuthenticationConverter())
                ));

        return http.build();
    }

    /**
     * Цепочка №2: всё остальное — публичное API.
     * - publicJwtDecoder без проверки aud.
     * - Роли USER/ADMIN для операций записи.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain publicChain(
        HttpSecurity http,
        @Qualifier("publicJwtDecoder") JwtDecoder publicJwtDecoder, ObjectMapper mapper,
        @Value("${spring.h2.console.enabled:false}") boolean consoleEnabled,
        @Value("${spring.h2.console.path:/h2-console}") String consolePath) throws Exception {

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(ApiSecurityHandlers.unauthorized(mapper))
                .accessDeniedHandler(ApiSecurityHandlers.forbidden(mapper)))
            .authorizeHttpRequests(auth -> {
                auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll();
                if (consoleEnabled) auth.requestMatchers(consolePath, consolePath + "/**").permitAll();
                auth.requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/hotels/**", "/api/rooms/**").hasAnyRole("USER", "ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/hotels/**", "/api/rooms/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PUT, "/api/hotels/**", "/api/rooms/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.PATCH, "/api/hotels/**", "/api/rooms/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.DELETE, "/api/hotels/**", "/api/rooms/**").hasRole("ADMIN")
                    .anyRequest().hasAnyRole("USER", "ADMIN");
            })
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(ApiSecurityHandlers.unauthorized(mapper))
                .accessDeniedHandler(ApiSecurityHandlers.forbidden(mapper))
                .jwt(jwt -> jwt.decoder(publicJwtDecoder)
                    .jwtAuthenticationConverter(new CustomJwtAuthenticationConverter())));
        if (consoleEnabled) http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }
}
