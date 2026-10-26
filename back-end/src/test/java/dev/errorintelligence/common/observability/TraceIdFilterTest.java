package dev.errorintelligence.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {
  @Test
  void ignoresCallerControlledCorrelationAndClearsThreadContext() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader("X-Trace-Id", "caller-controlled");
    var response = new MockHttpServletResponse();
    new TraceIdFilter()
        .doFilter(
            request,
            response,
            (req, res) ->
                assertThat(MDC.get("traceId")).isEqualTo(response.getHeader("X-Trace-Id")));
    assertThat(response.getHeader("X-Trace-Id"))
        .matches("[0-9a-f-]{36}")
        .isNotEqualTo("caller-controlled");
    assertThat(MDC.get("traceId")).isNull();
  }

  @Test
  void clearsCorrelationEvenWhenDownstreamThrows() {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    assertThrows(
        ServletException.class,
        () ->
            new TraceIdFilter()
                .doFilter(
                    request,
                    response,
                    (req, res) -> {
                      throw new ServletException("failure");
                    }));
    assertThat(MDC.get("traceId")).isNull();
  }
}
