package ru.mephi.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.convention.TestBean;

public abstract class GatewaySecurityTestSupport {
  private static final KeyPair KEYS = keys();
  private static final KeyPair OTHER_KEYS = keys();

  @TestBean(name = "gatewayPublicKey", methodName = "publicKey")
  RSAPublicKey gatewayPublicKey;

  public static RSAPublicKey publicKey() {
    return (RSAPublicKey) KEYS.getPublic();
  }

  private static KeyPair keys() {
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (GeneralSecurityException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  public static String token(String role) {
    return token(role, "hotel-booking-system", Instant.now().plusSeconds(3600), false);
  }

  static String token(String role, String issuer, Instant expires, boolean wrongKey) {
    KeyPair keys = wrongKey ? OTHER_KEYS : KEYS;
    var jwk =
        new RSAKey.Builder((RSAPublicKey) keys.getPublic())
            .privateKey((RSAPrivateKey) keys.getPrivate())
            .build();
    var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .subject("gateway-test")
            .issuedAt(expires.minusSeconds(3600))
            .expiresAt(expires)
            .claim("role", role)
            .build();
    return encoder
        .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
        .getTokenValue();
  }
}
