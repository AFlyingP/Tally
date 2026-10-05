package tally.platform.security;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tally.platform.error.ApiErrorHandler;
import tally.platform.error.ErrorCode;
import tally.platform.web.RequestIdFilter;

/** Validates access tokens and answers 401 and 403 in the error shape. */
@Configuration
@ConditionalOnProperty("tally.auth.jwks-uri")
@EnableMethodSecurity
public class ResourceServerConfig {

  private final ApiErrorHandler errors;
  private final ApplicationEventPublisher events;

  public ResourceServerConfig(ApiErrorHandler errors, ApplicationEventPublisher events) {
    this.errors = errors;
    this.events = events;
  }

  @Bean
  SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
    AuthenticationEntryPoint unauthenticated =
        (request, response, e) -> {
          events.publishEvent(denied(request, "anonymous", null, null, ErrorCode.UNAUTHENTICATED));
          response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
          errors.write(response, ErrorCode.UNAUTHENTICATED, "a valid access token is required");
        };
    AccessDeniedHandler forbidden =
        (request, response, e) -> {
          Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
          Jwt jwt = (Jwt) authentication.getPrincipal();
          Caller caller = Caller.from(jwt);
          events.publishEvent(
              denied(
                  request,
                  caller.clientId(),
                  caller.role(),
                  jwt.getClaimAsString("scope"),
                  ErrorCode.FORBIDDEN));
          errors.write(response, ErrorCode.FORBIDDEN, "the token lacks the required scope");
        };
    Filter clientIdLogging =
        (request, response, chain) -> {
          Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
          if (authentication != null) {
            MDC.put(RequestIdFilter.CLIENT_ID, authentication.getName());
          }
          chain.doFilter(request, response);
        };
    return http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> a.requestMatchers("/health", "/ready").permitAll().anyRequest().authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(jwt -> {})
                    .authenticationEntryPoint(unauthenticated)
                    .accessDeniedHandler(forbidden))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden))
        .addFilterAfter(clientIdLogging, BearerTokenAuthenticationFilter.class)
        .build();
  }

  @Bean
  JwtDecoder jwtDecoder(
      @Value("${tally.auth.jwks-uri}") String jwksUri,
      @Value("${tally.auth.issuer}") String issuer) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            new JwtTimestampValidator(Duration.ZERO),
            new JwtIssuerValidator(issuer),
            new JwtClaimValidator<List<String>>(
                "aud", audience -> audience != null && audience.contains("tally"))));
    return decoder;
  }

  private static RequestDenied denied(
      HttpServletRequest request, String clientId, String role, String scopes, ErrorCode code) {
    return new RequestDenied(
        clientId,
        role,
        scopes,
        request.getMethod(),
        request.getRequestURI(),
        RequestIdFilter.current(),
        code);
  }
}
