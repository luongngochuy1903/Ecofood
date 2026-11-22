package com.ecofood.app.submission.entity;

import com.ecofood.app.transaction.dto.BatchTransactionDtos.ReceiptResult;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Batch envelope in the SAME Submission collection, shared by KPI and seller email consumers. */
@Document("submission")
public record InvoiceBatchSubmission(@Id String id, String event, Instant createdAt, String tenantId,
    String sourceType, String sourceId, String propositionId, String planId, String orchestratorId,
    int orchestratorVersion, List<String> invoiceIds, String sourcePayloadFingerprint,
    String submittedBy, List<ReceiptResult> results, Confirmation sellerConfirmation) {
  public InvoiceBatchSubmission { invoiceIds=List.copyOf(invoiceIds); results=List.copyOf(results); }
  public static InvoiceBatchSubmission create(String id,Instant now,String tenant,List<String> invoiceIds,
      String fingerprint,String subject,List<ReceiptResult> results) {
    return new InvoiceBatchSubmission(id,Submission.INVOICE_PAID,now,tenant,"INVOICE_BATCH",id,
        null,null,null,0,invoiceIds,fingerprint,subject,results,new Confirmation(true,now,0));
  }
  public Submission envelope() { return new Submission(id,event,createdAt,tenantId,sourceType,sourceId,null,null,null,0); }
  public record Confirmation(boolean pending,Instant nextRetryAt,int attempts) {}
}
