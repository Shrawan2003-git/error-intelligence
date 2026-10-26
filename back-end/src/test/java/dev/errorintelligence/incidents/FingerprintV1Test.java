package dev.errorintelligence.incidents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FingerprintV1Test {
  private final FingerprintV1 fingerprints = new FingerprintV1();

  @Test
  void ignoresFrameLineNumbersRequestIdsAndVolatileRouteSegments() {
    String first =
        fingerprints.create(
            "com.example.PaymentException",
            "com.example.PaymentException: failed\n"
                + " at com.example.Payments.run(Payments.java:42) request_id=abcd-1234\n"
                + " at com.example.Client.send(Client.java:17)",
            "/api/payments/123?request_id=abc");
    String same =
        fingerprints.create(
            "com.example.PaymentException",
            "com.example.PaymentException: failed\n"
                + " at com.example.Payments.run(Payments.java:987) request_id=efgh-5678\n"
                + " at com.example.Client.send(Client.java:91)",
            "/api/payments/987?request_id=def");

    assertThat(first).hasSize(64).isEqualTo(same);
    assertThat(
            fingerprints.create(
                "com.example.DatabaseException",
                "at com.example.Payments.run(Payments.java:42)",
                "/api/payments/123"))
        .isNotEqualTo(first);
  }

  @Test
  void emitsStableSha256HexForTheSameEvidence() {
    assertThat(fingerprints.create("A", "at a.B.run(B.java:1)", "/health"))
        .isEqualTo(fingerprints.create("A", "at a.B.run(B.java:1)", "/health"));
  }
}
