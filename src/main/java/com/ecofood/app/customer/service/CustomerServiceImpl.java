package com.ecofood.app.customer.service;

import static com.ecofood.core.dto.CustomerDtos.*;

import com.ecofood.app.customer.entity.CustomerUser;
import com.ecofood.app.customer.security.CustomerAccess;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.core.dto.CustomerIdentityScope;
import com.ecofood.core.service.CustomerInvoiceService;
import com.ecofood.core.service.CustomerQueryService;
import com.ecofood.core.service.CustomerService;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService, CustomerQueryService,
    CustomerInvoiceService {
  private static final String CUSTOMER_PROFILE_REVISION = "customerProfileRevision";
  private final CustomerAccess access;
  private final MongoTemplate mongo;
  @Override
  public Profile profile() { return profile(access.current()); }
  private Profile profile(CustomerAccess.Actor actor) {
    var u = actor.user();
    return new Profile(actor.userId(), u.getUsername(), u.getEmail(), u.getFirstName(), u.getLastName(), "CUSTOMER", actor.tenantId(), u.getCustomerProfileRevision() == null ? 0 : u.getCustomerProfileRevision());
  }
  @Override
  public Profile update(UpdateProfile request) {
    var actor = access.current();
    if (request == null || request.revision() == null || request.revision() < 0 || request.firstName() == null || request.firstName().isBlank()
        || request.lastName() == null || request.lastName().isBlank() || request.firstName().length() > 100 || request.lastName().length() > 100)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Customer profile");
    Criteria revision = request.revision() == 0
        ? new Criteria().orOperator(Criteria.where(CUSTOMER_PROFILE_REVISION).is(0), Criteria.where(CUSTOMER_PROFILE_REVISION).is(null))
        : Criteria.where(CUSTOMER_PROFILE_REVISION).is(request.revision());
    Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(new ObjectId(actor.userId())).and("deleted").ne(true)
        .and("userCategory.userCategoryCode").is("CUSTOMER").and("partyRelationships").elemMatch(
          Criteria.where("relationshipTypeCode").is("TENANT").and("toPartyId").is(new ObjectId(actor.tenantId()))), revision));
    CustomerUser updated = mongo.findAndModify(query, new Update().set("firstName", request.firstName().trim()).set("lastName", request.lastName().trim())
        .set("updatedBy", actor.userId()).set("updatedAt", Instant.now()).inc(CUSTOMER_PROFILE_REVISION, 1), FindAndModifyOptions.options().returnNew(true), CustomerUser.class);
    if (updated == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Profile changed; reload before saving");
    return profile(new CustomerAccess.Actor(actor.userId(), actor.tenantId(), updated));
  }
  /** Shared ownership guard for future UC-019; never accepts a client-provided Customer ID. */
  @Override
  public Invoice requireOwned(CustomerIdentityScope actor, String id) {
    var query = owned(actor); query.addCriteria(Criteria.where("_id").is(id));
    Invoice invoice = mongo.findOne(query, Invoice.class);
    if (invoice == null) {
      throw unavailable();
    }
    return invoice;
  }
  Query owned(CustomerIdentityScope actor) {
    return Query.query(new Criteria().andOperator(Criteria.where("tenantId").is(actor.tenantId()).and("invoiceHolder.userId").is(actor.userId()),
        new Criteria().orOperator(Criteria.where("invoiceHolder.customerId").is(null), Criteria.where("invoiceHolder.customerId").is(actor.userId()))));
  }
  @Override
  public Page<OwnedInvoice> invoices(int page, int size) {
    var actor = access.current();
    if (page < 0 || page > 100000 || size < 1 || size > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid pagination");
    var rows = mongo.find(owned(actor).with(Sort.by(Sort.Direction.DESC, "receivedAt", "id")).skip((long) page * size).limit(size + 1), Invoice.class);
    return new Page<>(rows.stream().limit(size).map(CustomerServiceImpl::invoice).toList(), page, size, rows.size() > size);
  }
  @Override
  public OwnedInvoice detail(String id) { return invoice(requireOwned(access.current(), id)); }
  static OwnedInvoice invoice(Invoice i) {
    return new OwnedInvoice(i.getId(), i.getInvoiceNumber(), i.getType(), i.getStatus() == null ? null : i.getStatus().name(), i.getPaymentStatus(),
        i.getTransactionTime(), i.getReceivedAt(), i.getCurrency(), i.getTotalAmount(), i.getInvoiceDocumentId(),
        i.getItems() == null ? List.of() : i.getItems().stream().map(item -> new Item(item.productCode(), item.productName(), item.quantity(), item.unitPrice(), item.amount())).toList());
  }
  public static ResponseStatusException unavailable() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice unavailable"); }
}
