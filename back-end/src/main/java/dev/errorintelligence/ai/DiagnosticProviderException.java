package dev.errorintelligence.ai;

public class DiagnosticProviderException extends RuntimeException {
  private final String code;

  public DiagnosticProviderException(String code) {
    super(code);
    this.code = code;
  }

  public DiagnosticProviderException(String code, Throwable cause) {
    super(code, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
