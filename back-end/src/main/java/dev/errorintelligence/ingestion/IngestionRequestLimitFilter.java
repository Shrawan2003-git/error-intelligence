package dev.errorintelligence.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.common.api.ApiProblem;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class IngestionRequestLimitFilter extends OncePerRequestFilter {
  static final int MAX_BODY_BYTES = 256 * 1024;
  private final ObjectMapper mapper;

  public IngestionRequestLimitFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equalsIgnoreCase(request.getMethod())
        || !"/ingest/v1/events".equals(request.getRequestURI());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, jakarta.servlet.FilterChain chain)
      throws ServletException, IOException {
    MediaType contentType;
    try {
      contentType = MediaType.parseMediaType(request.getContentType());
    } catch (RuntimeException exception) {
      writeProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
      return;
    }
    if (!MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
      writeProblem(response, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE");
      return;
    }
    if (request.getContentLengthLong() > MAX_BODY_BYTES) {
      writeProblem(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
      return;
    }
    byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      writeProblem(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
      return;
    }
    chain.doFilter(new BufferedRequest(request, body), response);
  }

  private void writeProblem(HttpServletResponse response, HttpStatus status, String code)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader("Cache-Control", "no-store");
    mapper.writeValue(
        response.getOutputStream(),
        ApiProblem.create(status, code, "The ingestion request could not be accepted."));
  }

  private static final class BufferedRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    BufferedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          try {
            if (input.available() > 0) listener.onDataAvailable();
            if (input.available() == 0) listener.onAllDataRead();
          } catch (IOException exception) {
            listener.onError(exception);
          }
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
  }
}
