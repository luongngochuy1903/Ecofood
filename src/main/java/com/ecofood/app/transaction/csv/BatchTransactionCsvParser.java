package com.ecofood.app.transaction.csv;

import static com.ecofood.app.transaction.dto.BatchTransactionDtos.*;

import com.ecofood.app.config.BadRequestException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Builds the exact same {@link Request}/{@link Transaction}/{@link Receipt}/
 * {@link Item} records the JSON submit-transaction body already carries,
 * from a CSV upload — so BatchTransactionController's two endpoints (JSON
 * and CSV) converge on the one unchanged BatchTransactionService.submit().
 *
 * One CSV row = one Item. Rows are grouped into a Receipt by a CSV-only
 * "invoiceRef" column (never persisted, never the backend's own generated
 * invoice id — there is no caller-supplied one in this contract). Receipts
 * are emitted in the order their invoiceRef first appears; items keep their
 * row order within that group.
 *
 * invoiceHolder/additionalInfo are dynamic per Sale Orchestrator (see
 * PlanSaleOrchestrator.invoiceHolder/additionalInfoFields) — too dynamic for
 * fixed CSV columns, so any header prefixed "invoiceHolder." or "item."
 * is bucketed into that map instead (e.g. "invoiceHolder.email" ->
 * invoiceHolder.get("email")). invoiceHolder is per-Receipt, so every row
 * sharing the same invoiceRef must carry identical invoiceHolder.* values —
 * checked here, not left to silently take whichever row happened first.
 *
 * Every map value stays a plain String (never coerced to the configured
 * SaleConfigurationField.dataType) — the existing required-field checks
 * (BatchSalePlanResolver.requireAdditionalInfo/requireInvoiceHolder) only
 * test for non-null, so this is enough for feature parity with the JSON
 * path; a blank cell is treated as "key absent", not "key present with an
 * empty string", so it still fails a required check the same way a missing
 * JSON key would.
 */
@Component
public class BatchTransactionCsvParser {

  private static final String COL_INVOICE_REF = "invoiceRef";
  private static final String COL_PLAN_ID = "planId";
  private static final String COL_QUANTITY = "quantity";
  private static final String COL_ACTUAL_PRICE = "actualPrice";
  private static final Set<String> FIXED_COLUMNS =
      Set.of(COL_INVOICE_REF, COL_PLAN_ID, COL_QUANTITY, COL_ACTUAL_PRICE);
  private static final String INVOICE_HOLDER_PREFIX = "invoiceHolder.";
  private static final String ITEM_PREFIX = "item.";

  public Request parse(InputStream input) {
    try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        var parser = CSVParser.parse(reader, csvFormat())) {
      var headers = parser.getHeaderNames();
      requireKnownColumns(headers);
      requireColumnPresent(headers, COL_INVOICE_REF);
      requireColumnPresent(headers, COL_PLAN_ID);
      requireColumnPresent(headers, COL_QUANTITY);

      var groups = new LinkedHashMap<String, ReceiptGroup>();
      int rowNumber = 1; // header is row 1, first data row is row 2
      for (CSVRecord record : parser) {
        rowNumber++;
        addRow(groups, headers, record, rowNumber);
      }
      if (groups.isEmpty()) throw new BadRequestException("CSV file contains no data rows");

      var receipts = groups.values().stream().map(ReceiptGroup::toReceipt).toList();
      return new Request(new Transaction(receipts));
    } catch (IOException failure) {
      throw new BadRequestException("Unable to read CSV file: " + failure.getMessage());
    }
  }

  private CSVFormat csvFormat() {
    return CSVFormat.DEFAULT.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setTrim(true)
        .setIgnoreEmptyLines(true)
        .build();
  }

  private void requireKnownColumns(List<String> headers) {
    for (var header : headers) {
      if (FIXED_COLUMNS.contains(header)
          || header.startsWith(INVOICE_HOLDER_PREFIX)
          || header.startsWith(ITEM_PREFIX)) {
        continue;
      }
      throw new BadRequestException("Unknown CSV column: " + header);
    }
  }

  private void requireColumnPresent(List<String> headers, String column) {
    if (!headers.contains(column)) throw new BadRequestException("Missing required CSV column: " + column);
  }

  private void addRow(Map<String, ReceiptGroup> groups, List<String> headers, CSVRecord record, int rowNumber) {
    String invoiceRef = value(record, headers, COL_INVOICE_REF);
    if (invoiceRef == null) throw new BadRequestException("Row " + rowNumber + ": invoiceRef is required");

    String planId = value(record, headers, COL_PLAN_ID);
    if (planId == null) throw new BadRequestException("Row " + rowNumber + ": planId is required");

    BigDecimal quantity = decimal(record, headers, COL_QUANTITY, rowNumber);
    if (quantity == null) throw new BadRequestException("Row " + rowNumber + ": quantity is required");

    BigDecimal actualPrice = headers.contains(COL_ACTUAL_PRICE)
        ? decimal(record, headers, COL_ACTUAL_PRICE, rowNumber)
        : null;

    Map<String, Object> invoiceHolder = bucket(record, headers, INVOICE_HOLDER_PREFIX);
    Map<String, Object> additionalInfo = bucket(record, headers, ITEM_PREFIX);

    var group = groups.computeIfAbsent(invoiceRef, ref -> new ReceiptGroup(invoiceHolder, rowNumber));
    group.requireSameInvoiceHolder(invoiceHolder, invoiceRef, rowNumber);
    group.items.add(new Item(planId, quantity, actualPrice, additionalInfo.isEmpty() ? null : additionalInfo));
  }

  private String value(CSVRecord record, List<String> headers, String column) {
    if (!headers.contains(column)) return null;
    String raw = record.get(column);
    return raw == null || raw.isBlank() ? null : raw;
  }

  private BigDecimal decimal(CSVRecord record, List<String> headers, String column, int rowNumber) {
    String raw = value(record, headers, column);
    if (raw == null) return null;
    try {
      return new BigDecimal(raw);
    } catch (NumberFormatException invalid) {
      throw new BadRequestException("Row " + rowNumber + ": " + column + " must be numeric, got \"" + raw + "\"");
    }
  }

  private Map<String, Object> bucket(CSVRecord record, List<String> headers, String prefix) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var header : headers) {
      if (!header.startsWith(prefix)) continue;
      String key = header.substring(prefix.length());
      String raw = record.get(header);
      if (raw != null && !raw.isBlank()) result.put(key, raw);
    }
    return result;
  }

  private static final class ReceiptGroup {
    final Map<String, Object> invoiceHolder;
    final int firstRowNumber;
    final List<Item> items = new ArrayList<>();

    ReceiptGroup(Map<String, Object> invoiceHolder, int firstRowNumber) {
      this.invoiceHolder = invoiceHolder;
      this.firstRowNumber = firstRowNumber;
    }

    void requireSameInvoiceHolder(Map<String, Object> other, String invoiceRef, int rowNumber) {
      if (invoiceHolder.equals(other)) return;
      throw new BadRequestException(
          "Row " + rowNumber + ": invoiceHolder.* values differ from row " + firstRowNumber
              + " for invoiceRef \"" + invoiceRef + "\" — they must be identical across every item of the same invoice");
    }

    Receipt toReceipt() {
      return new Receipt(invoiceHolder.isEmpty() ? null : invoiceHolder, List.copyOf(items));
    }
  }
}
