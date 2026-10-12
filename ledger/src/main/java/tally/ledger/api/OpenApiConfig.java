package tally.ledger.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import java.util.LinkedHashMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.RequestHeader;

@Configuration
public class OpenApiConfig {

  @Bean
  OpenAPI ledgerOpenApi() {
    return new OpenAPI()
        .info(new Info().title("Tally ledger API").version("1"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList("bearer"));
  }

  @Bean
  OpenApiCustomizer ledgerSchemas() {
    return openApi -> {
      if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
        return;
      }
      for (Schema<?> schema : openApi.getComponents().getSchemas().values()) {
        var properties = schema.getProperties();
        if (properties != null) {
          schema.setProperties(new LinkedHashMap<>());
          properties.forEach(
              (name, property) -> schema.getProperties().put(snakeCase(name), property));
        }
        if (schema.getRequired() != null) {
          schema.setRequired(schema.getRequired().stream().map(OpenApiConfig::snakeCase).toList());
        }
      }
    };
  }

  @Bean
  OperationCustomizer ledgerOperations() {
    return (operation, handler) -> {
      PreAuthorize authorization = handler.getMethodAnnotation(PreAuthorize.class);
      if (authorization != null) {
        String expression = authorization.value();
        String scope =
            expression.substring(
                "hasAuthority('SCOPE_".length(), expression.length() - "')".length());
        operation.setDescription("scope: " + scope);
      }
      for (MethodParameter parameter : handler.getMethodParameters()) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null
            && ("Idempotency-Key".equals(header.name())
                || "Idempotency-Key".equals(header.value()))) {
          operation.getParameters().stream()
              .filter(p -> "Idempotency-Key".equals(p.getName()) && "header".equals(p.getIn()))
              .findFirst()
              .orElseThrow()
              .setRequired(true);
        }
      }
      return operation;
    };
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE)
  @ConditionalOnProperty(name = "springdoc.api-docs.enabled", havingValue = "true")
  SecurityFilterChain openApiSecurity(HttpSecurity http) throws Exception {
    return http.securityMatcher("/v3/api-docs")
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().permitAll())
        .build();
  }

  private static String snakeCase(String name) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char letter = name.charAt(i);
      if (letter >= 'A' && letter <= 'Z') {
        result.append('_').append(Character.toLowerCase(letter));
      } else {
        result.append(letter);
      }
    }
    return result.toString();
  }
}
