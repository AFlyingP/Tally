package tally.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Gives every request a correlation ID and writes one access log line for it. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";
  public static final String REQUEST_ID = "request_id";
  public static final String CLIENT_ID = "client_id";

  private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);
  private static final Pattern VALID = Pattern.compile("[A-Za-z0-9-]{8,64}");

  /** The request ID of the current thread, or a new UUID when none is set. */
  public static String current() {
    String id = MDC.get(REQUEST_ID);
    return id != null ? id : UUID.randomUUID().toString();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = request.getHeader(HEADER);
    if (id == null || !VALID.matcher(id).matches()) {
      id = UUID.randomUUID().toString();
    }
    MDC.put(REQUEST_ID, id);
    response.setHeader(HEADER, id);
    long start = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      long millis = (System.nanoTime() - start) / 1_000_000;
      log.info(
          "{} {} {} {}ms",
          request.getMethod(),
          request.getRequestURI(),
          response.getStatus(),
          millis);
      MDC.remove(REQUEST_ID);
      MDC.remove(CLIENT_ID);
    }
  }
}
