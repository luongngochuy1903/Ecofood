package com.ecofood.app.notification;

public interface NotificationEmailSender {
  void send(Notification notification);
  class DeliveryFailure extends RuntimeException {
    public final boolean retryable, uncertain;
    public final String code;
    public DeliveryFailure(String code, boolean retryable, boolean uncertain) {
      super(code); this.code = code; this.retryable = retryable; this.uncertain = uncertain;
    }
  }
}
