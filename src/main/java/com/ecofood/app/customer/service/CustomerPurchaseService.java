package com.ecofood.app.customer.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.customer.auth.CustomerIdentityService;
import com.ecofood.app.customer.dto.*;
import com.ecofood.app.customer.repository.CustomerPurchaseRepository;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.dto.PageResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CustomerPurchaseService {
  private final CustomerIdentityService identities;
  private final CustomerPurchaseRepository purchases;
  private static final Map<String, String> SORTS = Map.of("transactionTime", "transactionTime", "invoiceCode", "invoiceNumber",
      "totalAmount", "totalAmount", "distributionPartner", "distributionPartnerId", "currency", "currency", "status", "status");

  public PageResponse<CustomerInvoiceResponse> list(CustomerPurchaseFilter filter, int page, int size, String sort, String direction) {
    var scope = identities.requirePurchaseSession();
    if (page < 0 || size < 1 || size > 100) throw new BadRequestException("page must be >= 0 and size between 1 and 100");
    if (!SORTS.containsKey(sort) || !("asc".equalsIgnoreCase(direction) || "desc".equalsIgnoreCase(direction)))
      throw new BadRequestException("Unsupported purchase sort");
    var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(direction), SORTS.get(sort)).and(Sort.by(Sort.Direction.DESC, "_id")));
    var data = purchases.list(scope, filter, pageable);
    return PageResponse.<CustomerInvoiceResponse>builder().content(data.getContent().stream().map(invoice -> response(invoice, false)).toList())
        .page(data.getNumber()).size(data.getSize()).totalElements(data.getTotalElements()).totalPages(data.getTotalPages()).build();
  }

  public CustomerInvoiceResponse detail(String id) {
    var scope = identities.requirePurchaseSession();
    if (!ObjectId.isValid(id)) throw new BadRequestException("Invalid invoice identifier");
    return response(purchases.detail(scope, new ObjectId(id).toHexString()).orElseThrow(() -> new IllegalArgumentException("Invoice not found")), true);
  }

  public CustomerStatisticsResponse statistics(CustomerPurchaseFilter filter) { return statistics(filter, "UTC"); }
  public CustomerStatisticsResponse statistics(CustomerPurchaseFilter filter, String timeZone) {
    var scope = identities.requirePurchaseSession();
    if (timeZone == null || !java.time.ZoneId.getAvailableZoneIds().contains(timeZone)) throw new BadRequestException("Invalid statistics timeZone");
    return purchases.statistics(scope, filter, java.time.ZoneId.of(timeZone));
  }

  private CustomerInvoiceResponse response(Invoice invoice, boolean detail) {
    var source = invoice.getItems() == null ? List.<com.ecofood.core.dto.InvoiceItemDto>of() : invoice.getItems();
    var items = detail ? source.stream().map(line -> new CustomerInvoiceResponse.Item(line.productCode(), line.productName(), line.quantity(), line.unitPrice(), line.amount())).toList()
        : List.<CustomerInvoiceResponse.Item>of();
    BigDecimal subtotal = detail ? source.stream().map(com.ecofood.core.dto.InvoiceItemDto::amount).reduce(BigDecimal.ZERO, BigDecimal::add) : null;
    return new CustomerInvoiceResponse(invoice.getId(), invoice.getInvoiceNumber(), invoice.getDistributionPartnerId(), invoice.getTransactionTime(),
        invoice.getStatus() == null ? null : invoice.getStatus().name(), invoice.getCurrency(), invoice.getTotalAmount(), source.size(), items, subtotal);
  }
}
