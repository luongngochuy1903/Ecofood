package com.ecofood.app.metrics.service;

public class KpiProcessingException extends RuntimeException {
  private final String code;
  private final boolean retryable;
  public KpiProcessingException(String code, boolean retryable) {
    super(code);
    this.code = code;
    this.retryable = retryable;
  }
  public String code() { return code; }
  public boolean retryable() { return retryable; }
}
