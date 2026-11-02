package ru.mephi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import ru.mephi.api.ApiSecurityHandlers;

@Configuration
public class GatewaySecurityConfig {
  @Bean
  public RSAPublicKey gatewayPublicKey(@Value("${rsa.public-key}") Resource resource)
      throws Exception {
    try (InputStream input = resource.getInputStream()) {
      return RsaKeyConverters.x509().convert(input);
    }
  }

  @Bean
  public JwtDecoder gatewayJwtDecoder(RSAPublicKey gatewayPublicKey) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(gatewayPublicKey).build();
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer("hotel-booking-system"));
    return decoder;
  }

  @Bean
  @Order(1)
  public SecurityFilterChain businessApi(HttpSecurity http, ObjectMapper mapper) throws Exception {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("role");
    roles.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter authentication = new JwtAuthenticationConverter();
    authentication.setJwtGrantedAuthoritiesConverter(roles);
    http.securityMatcher("/api/**", "/actuator/**")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(ApiSecurityHandlers.unauthorized(mapper))
                    .accessDeniedHandler(ApiSecurityHandlers.forbidden(mapper)))
        .authorizeHttpRequests(
            auth ->
                auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login")
                    .permitAll()
                    .requestMatchers("/api/admin/**", "/actuator/**")
                    .hasRole("ADMIN")
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/hotels",
                        "/api/hotels/**",
                        "/api/rooms",
                        "/api/rooms/**")
                    .hasAnyRole("USER", "ADMIN")
                    .requestMatchers("/api/hotels", "/api/hotels/**", "/api/rooms", "/api/rooms/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .hasAnyRole("USER", "ADMIN"))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .authenticationEntryPoint(ApiSecurityHandlers.unauthorized(mapper))
                    .accessDeniedHandler(ApiSecurityHandlers.forbidden(mapper))
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(authentication)));
    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain documentationAndUnmappedPaths(HttpSecurity http) throws Exception {
    // No resource-server filter here: documentation stays public and hidden /internal paths stay
    // 404.
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
    return http.build();
  }
}
