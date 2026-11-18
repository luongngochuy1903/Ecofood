package com.ecofood.app.complaint;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class ComplaintIntakeConfiguration {
  @Bean public ComplaintAccessPolicy complaintAccessPolicy(MongoComplaintRoutingDirectory directory, Clock clock) { return new ComplaintAccessPolicy(directory, clock); }
  @Bean public RoutingPolicyValidator complaintRoutingValidator(MongoComplaintRoutingDirectory directory) { return new RoutingPolicyValidator(directory); }
  @Bean public RoutingPolicyResolver complaintRoutingResolver(MongoComplaintRoutingDirectory directory, RoutingPolicyValidator validator, ComplaintAccessPolicy access) {
    return new RoutingPolicyResolver(directory, validator, access);
  }
}
