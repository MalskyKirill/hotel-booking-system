package ru.mephi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Публичный каталог (просмотр доступен всем)
                .requestMatchers(HttpMethod.GET, "/api/hotels/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/rooms/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()

                // Административные операции
                .requestMatchers(HttpMethod.POST, "/api/hotels/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/hotels/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/hotels/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/rooms/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/rooms/**").hasRole("ADMIN")

                // Всё остальное требует аутентификации
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new CustomJwtAuthenticationConverter()))
            );

        http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }
}
