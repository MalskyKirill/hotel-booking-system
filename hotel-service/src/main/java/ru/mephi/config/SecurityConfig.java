package ru.mephi.config;

import org.springframework.beans.factory.annotation.Qualifier;
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
        @Qualifier("internalJwtDecoder") JwtDecoder internalJwtDecoder) throws Exception {

        http
            .securityMatcher("/internal/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().hasRole("SERVICE"))
            .oauth2ResourceServer(oauth2 -> oauth2
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
        @Qualifier("publicJwtDecoder") JwtDecoder publicJwtDecoder) throws Exception {

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // публичный каталог
                .requestMatchers(HttpMethod.GET, "/api/hotels/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/rooms/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                // админские операции
                .requestMatchers(HttpMethod.POST, "/api/hotels/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/hotels/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/hotels/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/rooms/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/rooms/**").hasRole("ADMIN")
                // всё остальное — с валидным токеном
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(publicJwtDecoder)
                    .jwtAuthenticationConverter(new CustomJwtAuthenticationConverter())
                ));

        http.headers(h -> h.frameOptions(f -> f.sameOrigin()));
        return http.build();
    }
}
