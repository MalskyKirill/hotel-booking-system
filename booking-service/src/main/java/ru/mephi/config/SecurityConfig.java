package ru.mephi.config;

import org.springframework.context.annotation.Bean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import jakarta.servlet.DispatcherType;
import ru.mephi.api.ApiSecurityHandlers;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper,
        @Value("${spring.h2.console.enabled:false}") boolean consoleEnabled,
        @Value("${spring.h2.console.path:/h2-console}") String consolePath) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(errors -> errors.authenticationEntryPoint(ApiSecurityHandlers.unauthorized(mapper))
                .accessDeniedHandler(ApiSecurityHandlers.forbidden(mapper)))
            .authorizeHttpRequests(auth -> {
                auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
                if (consoleEnabled) auth.requestMatchers(consolePath, consolePath + "/**").permitAll();
                auth.requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/bookings", "/api/bookings/**").hasAnyRole("USER", "ADMIN")
                    .anyRequest().hasAnyRole("USER", "ADMIN");
            })
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationEntryPoint(ApiSecurityHandlers.unauthorized(mapper))
                .accessDeniedHandler(ApiSecurityHandlers.forbidden(mapper))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(new CustomJwtAuthenticationConverter())));
        if (consoleEnabled) http.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));

        return http.build();
    }
}
