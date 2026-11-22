package com.ecofood.app.notification.exception;

public class DeliveryFailure extends RuntimeException {
  public final boolean retryable;
  public final boolean uncertain;
  public final String code;

  public DeliveryFailure(String code, boolean retryable, boolean uncertain) {
    super(code); this.code = code; this.retryable = retryable; this.uncertain = uncertain;
  }
}
