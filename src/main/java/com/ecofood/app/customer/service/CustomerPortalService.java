package com.ecofood.app.customer.service;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.customer.dto.CustomerInvoiceResponse;
import com.ecofood.app.customer.dto.CustomerProfileResponse;
import com.ecofood.app.customer.dto.CustomerProfileUpdateRequest;
import com.ecofood.app.customer.dto.CustomerStatisticsResponse;
import com.ecofood.app.customer.entity.CustomerInvoiceSnapshot;
import com.ecofood.app.customer.entity.CustomerProfileEntity;
import com.ecofood.app.customer.repository.CustomerInvoiceSnapshotRepository;
import com.ecofood.app.customer.repository.CustomerProfileRepository;
import com.ecofood.app.userACL.service.CustomerAccountLookupService;
import com.ecofood.app.userACL.service.CustomerAccountLookupService.CustomerAccount;
import com.ecofood.core.dto.PageResponse;
import com.ecofood.core.security.AuthenticationContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/**
 * Self-service customer APIs: user/tenant IDs ONLY from a verified JWT,
 * and app_user category + tenant membership checked on EVERY operation.
 */
@Service
@RequiredArgsConstructor
public class CustomerPortalService {
  private final CustomerAccountLookupService customerAccountLookupService;
  private final CustomerProfileRepository profileRepository;
  private final CustomerInvoiceSnapshotRepository invoiceRepository;
  private final MongoTemplate mongoTemplate;

  private CustomerAccount currentAccount() {
    return customerAccountLookupService.resolve(
        AuthenticationContext.getUserId(), AuthenticationContext.getTenantId());
  }

  public CustomerProfileResponse getMyProfile() {
    CustomerAccount account = currentAccount();
    CustomerProfileEntity profile = profileRepository
        .findByTenantIdAndUserIdAndDeletedNot(account.tenantId(), account.userId(), true)
        .orElse(null);
    return toProfileResponse(account, profile);
  }

  public CustomerProfileResponse updateMyProfile(CustomerProfileUpdateRequest request) {
    CustomerAccount account = currentAccount();
    CustomerProfileEntity profile = profileRepository
        .findByTenantIdAndUserIdAndDeletedNot(account.tenantId(), account.userId(), true)
        .orElseGet(() -> CustomerProfileEntity.builder()
            .tenantId(account.tenantId())
            .userId(account.userId())
            .firstName(account.firstName())
            .lastName(account.lastName())
            .build());

    List<String> changed = new ArrayList<>();
    if (request.firstName() != null) {
      String value = request.firstName().trim();
      if (value.isBlank()) throw new BadRequestException("First name cannot be blank");
      if (!Objects.equals(value, profile.getFirstName())) changed.add("firstName");
      profile.setFirstName(value);
    }
    if (request.lastName() != null) {
      String value = request.lastName().trim();
      if (value.isBlank()) throw new BadRequestException("Last name cannot be blank");
      if (!Objects.equals(value, profile.getLastName())) changed.add("lastName");
      profile.setLastName(value);
    }
    if (request.phoneNumber() != null) {
      String value = request.phoneNumber().trim();
      if (!Objects.equals(value, profile.getPhoneNumber())) changed.add("phoneNumber");
      profile.setPhoneNumber(value);
    }
    if (request.address() != null) {
      String value = request.address().trim();
      if (!Objects.equals(value, profile.getAddress())) changed.add("address");
      profile.setAddress(value);
    }

    if (!changed.isEmpty()) {
      if (profile.getChangeHistory() == null) profile.setChangeHistory(new ArrayList<>());
      profile.getChangeHistory().add(CustomerProfileEntity.ProfileChange.builder()
          .changedAt(Instant.now()).actorUserId(account.userId().toHexString())
          .changedFields(changed).build());
    }
    // Deliberately do NOT update app_user.email or Keycloak identity here.
    return toProfileResponse(account, profileRepository.save(profile));
  }

  public PageResponse<CustomerInvoiceResponse> getMyInvoices(int page, int size) {
    CustomerAccount account = currentAccount();
    PageRequest pagination = PageRequest.of(Math.max(page, 0),
        Math.max(1, Math.min(size, 50)), Sort.by(Sort.Direction.DESC, "saleDate"));
    Page<CustomerInvoiceSnapshot> data = invoiceRepository
        .findByTenantIdAndCustomerUserIdAndDeletedNot(
            account.tenantId(), account.userId(), true, pagination);
    return PageResponse.<CustomerInvoiceResponse>builder()
        .content(data.getContent().stream().map(this::toInvoiceResponse).toList())
        .page(data.getNumber()).size(data.getSize())
        .totalElements(data.getTotalElements()).totalPages(data.getTotalPages())
        .build();
  }

  public CustomerInvoiceResponse getMyInvoice(String invoiceId) {
    CustomerAccount account = currentAccount();
    if (!ObjectId.isValid(invoiceId)) throw new BadRequestException("Invalid invoice identifier");
    // Tenant and owner predicates are in the DB lookup, never a filter after fetch.
    return invoiceRepository.findByIdAndTenantIdAndCustomerUserIdAndDeletedNot(
            new ObjectId(invoiceId), account.tenantId(), account.userId(), true)
        .map(this::toInvoiceResponse)
        .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
  }

  public CustomerStatisticsResponse getMyStatistics() {
    CustomerAccount account = currentAccount();
    Aggregation query = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("tenantId").is(account.tenantId())
            .and("customerUserId").is(account.userId())
            .and("deleted").ne(true)),
        Aggregation.group("currency")
            .count().as("invoiceCount")
            .sum("totalAmount").as("totalAmount"),
        Aggregation.project("invoiceCount", "totalAmount")
            .and("_id").as("currency"));
    List<CurrencyAggregate> rows = mongoTemplate
        .aggregate(query, CustomerInvoiceSnapshot.class, CurrencyAggregate.class)
        .getMappedResults();
    long count = rows.stream().mapToLong(CurrencyAggregate::getInvoiceCount).sum();
    List<CustomerStatisticsResponse.CurrencySpend> spending = rows.stream()
        .map(row -> new CustomerStatisticsResponse.CurrencySpend(
            Objects.toString(row.getCurrency(), "N/A"),
            row.getInvoiceCount(),
            row.getTotalAmount() == null ? BigDecimal.ZERO : row.getTotalAmount()))
        .toList();
    return new CustomerStatisticsResponse(count, spending);
  }

  private CustomerProfileResponse toProfileResponse(CustomerAccount account,
      CustomerProfileEntity profile) {
    return new CustomerProfileResponse(
        account.userId().toHexString(), account.tenantId().toHexString(), account.email(),
        profile == null ? account.firstName() : profile.getFirstName(),
        profile == null ? account.lastName() : profile.getLastName(),
        profile == null ? "" : Objects.toString(profile.getPhoneNumber(), ""),
        profile == null ? "" : Objects.toString(profile.getAddress(), ""),
        profile == null ? null : profile.getUpdatedAt());
  }

  private CustomerInvoiceResponse toInvoiceResponse(CustomerInvoiceSnapshot item) {
    List<CustomerInvoiceResponse.Item> items = item.getItems() == null ? List.of()
        : item.getItems().stream().map(line -> new CustomerInvoiceResponse.Item(
            line.getProductId(), line.getProductName(), line.getQuantity(),
            line.getUnitPrice(), line.getLineTotal())).toList();
    return new CustomerInvoiceResponse(item.getIdAsString(), item.getInvoiceCode(),
        item.getDistributionPartnerName(), item.getSaleDate(), item.getStatus(),
        item.getCurrency(), item.getTotalAmount(), items.size(), items);
  }

  @Getter
  @Setter
  public static class CurrencyAggregate {
    private String currency;
    private long invoiceCount;
    private BigDecimal totalAmount;
  }
}
