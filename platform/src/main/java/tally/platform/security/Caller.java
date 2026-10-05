package tally.platform.security;

import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/** The authenticated client of a request, or a background job acting on its own. */
public record Caller(String clientId, String role, Set<String> scopes) {

  public static Caller from(Jwt jwt) {
    String subject = jwt.getSubject();
    if (subject == null) {
      throw new IllegalArgumentException("token has no sub claim");
    }
    String scope = jwt.getClaimAsString("scope");
    Set<String> scopes = scope == null || scope.isBlank() ? Set.of() : Set.of(scope.split(" "));
    return new Caller(subject, jwt.getClaimAsString("role"), scopes);
  }

  public static Caller job(String name) {
    return new Caller("job:" + name, null, Set.of());
  }
}
