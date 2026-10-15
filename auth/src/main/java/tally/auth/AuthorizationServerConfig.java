package tally.auth;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import tally.platform.security.Role;

@Configuration
public class AuthorizationServerConfig {

  @Bean
  @Order(1)
  SecurityFilterChain tokenEndpoints(HttpSecurity http) throws Exception {
    return http.oauth2AuthorizationServer(
            server -> http.securityMatcher(server.getEndpointsMatcher()))
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .build();
  }

  @Bean
  AuthorizationServerSettings authorizationServerSettings(AuthProperties properties) {
    return AuthorizationServerSettings.builder()
        .issuer(properties.issuer())
        .tokenEndpoint("/oauth2/token")
        .jwkSetEndpoint("/oauth2/jwks")
        .build();
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  JWKSource<SecurityContext> jwkSource(AuthProperties properties) {
    RSAKey key = SigningKeyStore.loadOrCreate(Path.of(properties.keyPath()));
    return new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(
        new com.nimbusds.jose.jwk.JWKSet(key));
  }

  /** Gives access tokens the claims the resource servers expect. */
  @Bean
  OAuth2TokenCustomizer<JwtEncodingContext> accessTokenClaims() {
    return context -> {
      if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
        return;
      }
      Role role = Role.fromWireName(context.getRegisteredClient().getClientName());
      context.getJwsHeader().type("JWT");
      context
          .getClaims()
          .claim("role", role.wireName())
          .claim("scope", String.join(" ", role.scopes().stream().sorted().toList()))
          .audience(List.of("tally"))
          .id(UUID.randomUUID().toString());
    };
  }
}
