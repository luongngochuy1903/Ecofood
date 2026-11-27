package com.ecofood.app.transaction.csv;

import static org.assertj.core.api.Assertions.*;

import com.ecofood.app.config.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BatchTransactionCsvParserTest {
  private final BatchTransactionCsvParser parser = new BatchTransactionCsvParser();

  private InputStream stream(String csv) {
    return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
  }

  @Test void groupsRowsByInvoiceRefPreservingFirstAppearanceAndItemOrder() {
    String csv = """
        invoiceRef,planId,quantity,actualPrice
        2,plan-ghi,3,9.50
        1,plan-abc,2,15.00
        1,plan-def,1,
        """;
    var request = parser.parse(stream(csv));
    var invoices = request.transaction().invoices();
    assertThat(invoices).hasSize(2);
    assertThat(invoices.get(0).items()).extracting("planId").containsExactly("plan-ghi");
    assertThat(invoices.get(1).items()).extracting("planId").containsExactly("plan-abc", "plan-def");
    assertThat(invoices.get(1).items().get(0).quantity()).isEqualByComparingTo(new BigDecimal("2"));
    assertThat(invoices.get(1).items().get(0).actualPrice()).isEqualByComparingTo(new BigDecimal("15.00"));
    assertThat(invoices.get(1).items().get(1).actualPrice()).isNull();
  }

  @Test void bucketsDynamicColumnsAndOmitsBlankCells() {
    String csv = """
        invoiceRef,planId,quantity,invoiceHolder.email,invoiceHolder.phone,item.cashierId
        1,plan-abc,2,a@x.com,,C-01
        """;
    var receipt = parser.parse(stream(csv)).transaction().invoices().get(0);
    assertThat(receipt.invoiceHolder()).containsExactly(java.util.Map.entry("email", "a@x.com"));
    assertThat(receipt.items().get(0).additionalInfo()).containsExactly(java.util.Map.entry("cashierId", "C-01"));
  }

  @Test void rejectsAnUnknownColumn() {
    // Unprefixed and not one of the fixed names — invoiceHolder.*/item.* keys are
    // dynamic by design (see BatchTransactionCsvParser's class javadoc) and always
    // allowed regardless of the key name after the prefix.
    String csv = """
        invoiceRef,planId,quantity,customerNote
        1,plan-abc,2,hello
        """;
    assertThatThrownBy(() -> parser.parse(stream(csv)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Unknown CSV column: customerNote");
  }

  @Test void rejectsAMissingRequiredColumn() {
    String csv = """
        planId,quantity
        plan-abc,2
        """;
    assertThatThrownBy(() -> parser.parse(stream(csv)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Missing required CSV column: invoiceRef");
  }

  @Test void rejectsNonNumericQuantityWithARowNumber() {
    String csv = """
        invoiceRef,planId,quantity
        1,plan-abc,two
        """;
    assertThatThrownBy(() -> parser.parse(stream(csv)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Row 2").hasMessageContaining("quantity must be numeric");
  }

  @Test void rejectsInconsistentInvoiceHolderWithinTheSameInvoiceRef() {
    String csv = """
        invoiceRef,planId,quantity,invoiceHolder.email
        1,plan-abc,2,a@x.com
        1,plan-def,1,different@x.com
        """;
    assertThatThrownBy(() -> parser.parse(stream(csv)))
        .isInstanceOf(BadRequestException.class)
        .hasMessageContaining("Row 3").hasMessageContaining("invoiceHolder.*");
  }

  @Test void rejectsABlankInvoiceRefOrPlanId() {
    assertThatThrownBy(() -> parser.parse(stream("invoiceRef,planId,quantity\n,plan-abc,2\n")))
        .isInstanceOf(BadRequestException.class).hasMessageContaining("invoiceRef is required");
    assertThatThrownBy(() -> parser.parse(stream("invoiceRef,planId,quantity\n1,,2\n")))
        .isInstanceOf(BadRequestException.class).hasMessageContaining("planId is required");
  }

  @Test void rejectsAnEmptyFileWithNoDataRows() {
    assertThatThrownBy(() -> parser.parse(stream("invoiceRef,planId,quantity\n")))
        .isInstanceOf(BadRequestException.class).hasMessageContaining("no data rows");
  }
}
