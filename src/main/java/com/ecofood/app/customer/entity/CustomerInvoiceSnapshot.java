package com.ecofood.app.customer.entity;

import com.ecofood.core.entity.BaseEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Temporary READ MODEL for UC-021 while UC-015 is not implemented.
 * Populate only with test fixtures; replace the repository adapter with the
 * real Invoice read service after agreeing its contract with UC-015's owner.
 * Never expose a public endpoint to create/change these snapshots.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "customer_invoice_snapshot")
@CompoundIndex(name = "idx_customer_invoice_scope_date",
    def = "{'tenant_identifier': 1, 'customer_user_identifier': 1, 'sale_date': -1}")
public class CustomerInvoiceSnapshot extends BaseEntity {
  @Field("tenant_identifier")
  private ObjectId tenantId;

  @Field("customer_user_identifier")
  private ObjectId customerUserId;

  @Field("invoice_code")
  private String invoiceCode;

  @Field("distribution_partner_name")
  private String distributionPartnerName;

  @Field("sale_date")
  private Instant saleDate;

  @Field("status")
  private String status;

  @Field("currency")
  private String currency;

  @Field("total_amount")
  private BigDecimal totalAmount;

  @Field("items")
  @Builder.Default
  private List<InvoiceItem> items = new ArrayList<>();

  @Getter
  @Setter
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class InvoiceItem {
    @Field("product_identifier")
    private String productId;
    @Field("product_name")
    private String productName;
    @Field("quantity")
    private BigDecimal quantity;
    @Field("unit_price")
    private BigDecimal unitPrice;
    @Field("line_total")
    private BigDecimal lineTotal;
  }
}
