package com.ecofood.app.transaction.service;

import static com.ecofood.app.transaction.dto.BatchTransactionDtos.*;
import com.ecofood.app.config.*;
import com.ecofood.app.metrics.service.MongoMetricsTransactions;
import com.ecofood.app.orchestrator.service.BatchSalePlanResolver;
import com.ecofood.app.notification.service.SaleConfirmationHandoff;
import com.ecofood.app.submission.entity.InvoiceBatchSubmission;
import com.ecofood.app.transaction.entity.*;
import com.ecofood.core.enums.TransactionStatus;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BatchTransactionService {
  private final MongoMetricsTransactions transactions;
  private final BatchSalePlanResolver plans;
  private final SaleConfirmationHandoff confirmations;
  private final Validator validator;

  public Response submit(String tenant,String subject,Request request) {
    if(tenant==null || tenant.isBlank() || subject==null || subject.isBlank()) throw new ForbiddenException("Verified Tenant and caller required");
    if(request==null || !validator.validate(request).isEmpty()) throw new BadRequestException("Invalid transaction batch");
    if(request.transaction().invoices().stream().mapToInt(r->r.items().size()).sum()>1000)
      throw new BadRequestException("Batch exceeds 1000 Plan lines");
    var numbers=new TreeSet<String>();
    for(var receipt:request.transaction().invoices()) if(!numbers.add(receipt.invoiceNumber())) throw new BadRequestException("Duplicate invoiceNumber in batch");
    String submissionId="sale-batch:"+hash(List.of(tenant,subject,numbers));
    String fingerprint=hash(request.transaction().invoices().stream().sorted(Comparator.comparing(Receipt::invoiceNumber)).map(this::canonical).toList());
    var batch=transactions.execute(ops->{
      var query=Query.query(Criteria.where("_id").is(submissionId).and("tenantId").is(tenant));
      var previous=ops.findOne(query,InvoiceBatchSubmission.class);
      if(previous!=null) {
        if(!fingerprint.equals(previous.sourcePayloadFingerprint())) throw conflict();
        return previous;
      }
      var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
      var results=new ArrayList<ReceiptResult>(); var invoices=new ArrayList<Invoice>();
      for(var receipt:request.transaction().invoices()) {
        var existing=ops.findOne(Query.query(Criteria.where("tenantId").is(tenant).and("posId").is(subject)
            .and("invoiceNumber").is(receipt.invoiceNumber())),Invoice.class);
        if(existing!=null) { results.add(rejected(receipt,"INVOICE_ALREADY_SUBMITTED","Invoice belongs to an earlier Submit; do not include it in a new batch")); continue; }
        try {
          var invoice=prepare(tenant,subject,receipt,now); invoice.setSubmissionId(submissionId);
          invoices.add(invoice); results.add(new ReceiptResult(receipt.invoiceNumber(),receipt.items(),"ACCEPTED",null));
        } catch(BadRequestException invalid) { results.add(rejected(receipt,"INVALID_INVOICE",invalid.getMessage())); }
        catch(ForbiddenException denied) { results.add(rejected(receipt,"SCOPE_DENIED","Plan or Orchestrator unavailable for this Tenant")); }
        catch(ResponseStatusException failure) { results.add(rejected(receipt,"INVOICE_"+failure.getStatusCode().value(),"Plan configuration unavailable")); }
        catch(IllegalStateException unavailable) { results.add(rejected(receipt,"CONFIGURATION_UNAVAILABLE","Active configuration unavailable")); }
      }
      if(invoices.isEmpty()) return InvoiceBatchSubmission.create(submissionId,now,tenant,List.of(),fingerprint,subject,results);
      var event=InvoiceBatchSubmission.create(submissionId,now,tenant,invoices.stream().map(Invoice::getId).toList(),fingerprint,subject,results);
      // Accepted Invoices and ONE shared event commit or abort together.
      for(var invoice:invoices) ops.insert(invoice);
      ops.insert(event);
      return event;
    });
    if(!batch.invoiceIds().isEmpty()) confirmations.deliver(batch);
    var byNumber=new HashMap<String,ReceiptResult>(); batch.results().forEach(r->byNumber.put(r.invoiceNumber(),r));
    return new Response(new Result(request.transaction().invoices().stream().map(r->byNumber.get(r.invoiceNumber())).toList()));
  }
  private ReceiptResult rejected(Receipt receipt,String code,String message) { return new ReceiptResult(receipt.invoiceNumber(),receipt.items(),"REJECTED",new com.ecofood.app.transaction.dto.BatchTransactionDtos.Error(code,message)); }
  private Invoice prepare(String tenant,String subject,Receipt receipt,Instant now) {
    var lines=new ArrayList<InvoicePlanLine>(); var ids=new HashSet<String>(); String currency=null;
    for(var item:receipt.items()) {
      if(!ids.add(item.lineId())) throw new BadRequestException("Duplicate lineId in Invoice");
      if(item.unitPrice().multiply(item.quantity()).compareTo(item.amount())!=0) throw new BadRequestException("Item amount must equal quantity * unitPrice");
      var line=plans.resolve(tenant,subject,receipt.invoiceNumber(),item,now);
      String lineCurrency=line.snapshot().currency();
      if(currency!=null && !currency.equals(lineCurrency)) throw new BadRequestException("Invoice cannot mix currencies");
      currency=lineCurrency; lines.add(line);
    }
    var invoice=new Invoice(); invoice.setId(new ObjectId().toHexString()); invoice.setTenantId(tenant);
    invoice.setSubmittedBy(subject); invoice.setPosId(subject); invoice.setDistributionPartnerId(tenant);
    invoice.setInvoiceNumber(receipt.invoiceNumber()); invoice.setCurrency(currency); invoice.setReceivedAt(now);
    invoice.setTransactionTimeSource("NOT_SUPPLIED"); invoice.setType("PLAN_SALE_BATCH");
    invoice.setStatus(TransactionStatus.ACCEPTED); invoice.setPaymentStatus("PAID");
    invoice.setTotalAmount(lines.stream().map(InvoicePlanLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add));
    invoice.setPlanLines(List.copyOf(lines)); invoice.setSourcePayloadFingerprint(hash(canonical(receipt)));
    invoice.setItems(List.of()); invoice.setPostWorkflowResults(Map.of());
    return invoice;
  }
  private Receipt canonical(Receipt receipt) {
    return new Receipt(receipt.invoiceNumber(),receipt.items().stream().sorted(Comparator.comparing(Item::lineId))
        .map(i->new Item(i.lineId(),i.orchestratorId(),i.planId(),i.quantity(),i.unitPrice().stripTrailingZeros(),i.amount().stripTrailingZeros())).toList());
  }
  private ResponseStatusException conflict() { return new ResponseStatusException(HttpStatus.CONFLICT,"Batch invoice numbers already used with different content"); }
  private String hash(Object value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(value))); }
    catch(Exception failure) { throw new IllegalStateException("Cannot fingerprint batch",failure); }
  }
}
