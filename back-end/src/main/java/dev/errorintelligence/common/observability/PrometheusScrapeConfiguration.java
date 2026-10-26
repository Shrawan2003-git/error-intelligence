package dev.errorintelligence.common.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PrometheusScrapeConfiguration {
  @Bean
  PrometheusScrapeFilter prometheusScrapeFilter(
      @Value("${app.observability.prometheus-allowed-cidrs:127.0.0.1/32,::1/128}") String cidrs) {
    return new PrometheusScrapeFilter(cidrs);
  }

  @Bean
  FilterRegistrationBean<PrometheusScrapeFilter> prometheusScrapeFilterRegistration(
      PrometheusScrapeFilter filter) {
    FilterRegistrationBean<PrometheusScrapeFilter> registration = new FilterRegistrationBean<>();
    registration.setFilter(filter);
    registration.setEnabled(false);
    return registration;
  }
}
