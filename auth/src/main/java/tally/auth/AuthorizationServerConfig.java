package tally.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import tally.platform.error.ApiErrorHandler;
import tally.platform.error.ErrorCode;
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
  @Order(2)
  SecurityFilterChain otherPaths(HttpSecurity http, ApiErrorHandler errors) throws Exception {
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> a.requestMatchers("/health", "/ready").permitAll().anyRequest().authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                    (request, response, failure) -> {
                      response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                      errors.write(
                          response, ErrorCode.UNAUTHENTICATED, "a valid access token is required");
                    }))
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
  RegisteredClientRepository registeredClientRepository(BootstrapClients clients) {
    return new InMemoryRegisteredClientRepository(clients.registeredClients());
  }

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(10);
  }

  @Bean
  JWKSource<SecurityContext> jwkSource() throws NoSuchAlgorithmException {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    KeyPair pair = generator.generateKeyPair();
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(pair.getPublic().getEncoded());
    RSAKey key =
        new RSAKey.Builder((RSAPublicKey) pair.getPublic())
            .privateKey((RSAPrivateKey) pair.getPrivate())
            .keyID(HexFormat.of().formatHex(digest).substring(0, 16))
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .build();
    return new ImmutableJWKSet<>(new JWKSet(key));
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
