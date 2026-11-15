package com.ecofood.app.feedback.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.feedback.dto.*;
import com.ecofood.app.feedback.entity.ServiceFeedback;
import com.ecofood.app.feedback.entity.ServiceFeedbackStatus;
import com.ecofood.app.feedback.repository.ServiceFeedbackRepository;
import com.ecofood.app.receipt.service.ReceiptService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ServiceFeedbackService {
  private final ReceiptService receiptService;
  private final ServiceFeedbackRepository feedbacks;
  private final Validator validator;

  public ServiceFeedbackStatusResponse submit(String token, SubmitServiceFeedbackRequest request) {
    var invoice = receiptService.requireValidInvoice(token);
    if (request == null || !validator.validate(request).isEmpty())
      throw new BadRequestException("Invalid service feedback payload");
    if (feedbacks.existsByTenantIdAndInvoiceId(invoice.getTenantId(), invoice.getId())) throw duplicate();
    ServiceFeedback feedback = new ServiceFeedback();
    feedback.setInvoiceId(invoice.getId()); feedback.setTenantId(invoice.getTenantId());
    feedback.setDistributionPartnerId(invoice.getDistributionPartnerId());
    feedback.setRating(request.rating().intValueExact());
    String comment = request.comment() == null ? null : request.comment().strip();
    feedback.setComment(comment == null || comment.isEmpty() ? null : comment);
    feedback.setStatus(ServiceFeedbackStatus.SUBMITTED);
    // BaseEntity auditing supplies id/createdAt; never attribute Guest feedback to a JWT user.
    try { feedbacks.insert(feedback); }
    catch (DuplicateKeyException ex) {
      if (feedbacks.existsByTenantIdAndInvoiceId(invoice.getTenantId(), invoice.getId())) throw duplicate();
      throw ex;
    }
    return new ServiceFeedbackStatusResponse(true);
  }

  public ServiceFeedbackStatusResponse status(String token) {
    var invoice = receiptService.requireValidInvoice(token);
    return new ServiceFeedbackStatusResponse(
        feedbacks.existsByTenantIdAndInvoiceId(invoice.getTenantId(), invoice.getId()));
  }

  private IllegalStateException duplicate() { return new IllegalStateException("Service feedback already submitted"); }
}
