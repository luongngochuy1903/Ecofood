package com.ecofood.app.complaint;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ComplaintAuthorization {
  private final ObjectProvider<ComplaintAccessPolicy> policies;
  public ComplaintAccessPolicy requirePolicy() {
    var policy = policies.getIfAvailable();
    if (policy == null) throw new IllegalStateException("Complaint authorization adapter is not configured");
    return policy;
  }
}
