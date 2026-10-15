package tally.extbank;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;
import tally.platform.error.ApiErrorHandler;
import tally.platform.error.ErrorCode;

public class ApiKeyFilter extends OncePerRequestFilter {

  private static final String HEADER = "X-Api-Key";
  private final byte[] apiKey;
  private final ApiErrorHandler errors;

  public ApiKeyFilter(String apiKey, ApiErrorHandler errors) {
    if (apiKey == null || apiKey.length() < 32) {
      throw new IllegalArgumentException(
          "invalid configuration: tally.extbank.api-key: must be at least 32 characters");
    }
    this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI();
    if (path.equals("/health") || path.equals("/ready")) {
      chain.doFilter(request, response);
      return;
    }
    String supplied = request.getHeader(HEADER);
    if (supplied == null
        || !MessageDigest.isEqual(apiKey, supplied.getBytes(StandardCharsets.UTF_8))) {
      errors.write(response, ErrorCode.UNAUTHENTICATED, "a valid api key is required");
      return;
    }
    chain.doFilter(request, response);
  }
}
