package dev.errorintelligence.identity;

public interface AccountMailDelivery {
  void send(String recipient, String subject, String body);
}
