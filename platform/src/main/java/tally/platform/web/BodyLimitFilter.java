package tally.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tally.platform.error.ApiErrorHandler;
import tally.platform.error.ApiException;
import tally.platform.error.ErrorCode;

/** Rejects request bodies above the size limit of their path. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class BodyLimitFilter extends OncePerRequestFilter {

  private static final long MIN_LIMIT = 1024;
  private static final long MAX_LIMIT = 104_857_600;
  private static final String TOO_LARGE = "request body is too large";

  private final ApiErrorHandler errors;
  private final long maxBodyBytes;
  private final Set<String> largeBodyPaths;
  private final long largeBodyBytes;

  public BodyLimitFilter(
      ApiErrorHandler errors,
      @Value("${tally.web.max-body-bytes:1048576}") long maxBodyBytes,
      @Value("${tally.web.large-body-paths:}") Set<String> largeBodyPaths,
      @Value("${tally.web.large-body-bytes:12582912}") long largeBodyBytes) {
    this.errors = errors;
    this.maxBodyBytes = inRange("tally.web.max-body-bytes", maxBodyBytes);
    this.largeBodyPaths = Set.copyOf(largeBodyPaths);
    this.largeBodyBytes = inRange("tally.web.large-body-bytes", largeBodyBytes);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long limit = largeBodyPaths.contains(request.getRequestURI()) ? largeBodyBytes : maxBodyBytes;
    long declared = request.getContentLengthLong();
    if (declared > limit) {
      errors.write(response, ErrorCode.PAYLOAD_TOO_LARGE, TOO_LARGE);
    } else if (declared >= 0) {
      chain.doFilter(request, response);
    } else {
      chain.doFilter(new LimitedRequest(request, limit), response);
    }
  }

  private static long inRange(String key, long value) {
    if (value < MIN_LIMIT || value > MAX_LIMIT) {
      throw new IllegalArgumentException(
          "invalid configuration: %s: must be between %d and %d"
              .formatted(key, MIN_LIMIT, MAX_LIMIT));
    }
    return value;
  }

  /** A request without a declared length: the limit is enforced while the body is read. */
  private static final class LimitedRequest extends HttpServletRequestWrapper {

    private final long limit;

    LimitedRequest(HttpServletRequest request, long limit) {
      super(request);
      this.limit = limit;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      return new LimitedStream(super.getInputStream(), limit);
    }
  }

  private static final class LimitedStream extends ServletInputStream {

    private final ServletInputStream in;
    private final long limit;
    private long count;

    LimitedStream(ServletInputStream in, long limit) {
      this.in = in;
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      int b = in.read();
      if (b >= 0) {
        counted(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = in.read(buffer, offset, length);
      if (n > 0) {
        counted(n);
      }
      return n;
    }

    private void counted(int bytes) {
      count += bytes;
      if (count > limit) {
        throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE, TOO_LARGE);
      }
    }

    @Override
    public boolean isFinished() {
      return in.isFinished();
    }

    @Override
    public boolean isReady() {
      return in.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      in.setReadListener(listener);
    }
  }
}
