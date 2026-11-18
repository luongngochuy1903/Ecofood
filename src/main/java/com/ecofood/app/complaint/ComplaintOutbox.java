package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Delivery state is operational metadata; event payload is immutable and contains no description/proof. */
@Getter @Setter @Document("complaint_outbox")
public class ComplaintOutbox {
  @Id private String id;
  private DomainEvent event;
  private DispatchState state = DispatchState.NEW;
  private int attempts;
  private Instant nextAttemptAt;
  private String consumerId;
  private String leaseToken;
  private Instant leaseUntil;
  private Instant acknowledgedAt;
  private String lastErrorCode;
}
