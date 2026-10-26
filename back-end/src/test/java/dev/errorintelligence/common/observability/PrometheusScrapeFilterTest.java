package dev.errorintelligence.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PrometheusScrapeFilterTest {
  @Test
  void permitsOnlyConfiguredScrapeNetworks() throws Exception {
    var filter = new PrometheusScrapeFilter("127.0.0.1/32,10.20.0.0/16");
    var local = request("127.0.0.1");
    var localResponse = new MockHttpServletResponse();
    filter.doFilter(local, localResponse, (request, response) -> {});
    assertThat(localResponse.getStatus()).isEqualTo(200);

    var remote = request("192.0.2.44");
    var remoteResponse = new MockHttpServletResponse();
    filter.doFilter(remote, remoteResponse, (request, response) -> {});
    assertThat(remoteResponse.getStatus()).isEqualTo(404);
  }

  private MockHttpServletRequest request(String address) {
    var request = new MockHttpServletRequest();
    request.setRequestURI("/actuator/prometheus");
    request.setRemoteAddr(address);
    return request;
  }
}
