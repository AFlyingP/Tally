package tally.ledger.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tally.platform.error.ApiErrorHandler;
import tally.platform.error.ErrorCode;

/** Hides the API in a process that runs only worker roles. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ApiRoleFilter extends OncePerRequestFilter {

  private final boolean apiEnabled;
  private final ApiErrorHandler errors;

  public ApiRoleFilter(LedgerProperties properties, ApiErrorHandler errors) {
    this.apiEnabled = properties.has("api");
    this.errors = errors;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!apiEnabled && request.getRequestURI().startsWith("/v1/")) {
      errors.write(response, ErrorCode.NOT_FOUND, "path does not exist");
    } else {
      chain.doFilter(request, response);
    }
  }
}
