package com.ecofood.app.transaction;

import com.ecofood.app.config.BadRequestException;
import com.ecofood.app.transaction.dto.TransactionFilter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransactionFilterTest {
  @Test void acceptsEqualDateBoundariesAndOneSidedRanges() {
    Instant boundary = Instant.parse("2026-11-01T00:00:00Z");
    assertDoesNotThrow(() -> new TransactionFilter(null, null, boundary, boundary, null));
    assertDoesNotThrow(() -> new TransactionFilter(null, null, boundary, null, null));
    assertDoesNotThrow(() -> new TransactionFilter(null, null, null, boundary, null));
  }

  @Test void rejectsReversedDatesAndMalformedInvoiceIdentifiers() {
    assertThrows(BadRequestException.class,
        () -> new TransactionFilter(null, null, Instant.now(), Instant.EPOCH, null));
    assertThrows(BadRequestException.class,
        () -> new TransactionFilter("INV-1", null, null, null, null));
    assertThrows(BadRequestException.class,
        () -> new TransactionFilter("", null, null, null, null));
  }

  @Test void normalizesInvoiceNumberAndRejectsOversizedSearch() {
    assertEquals("INV.*", new TransactionFilter(null, "  INV.*  ", null, null, null).invoiceNumber());
    assertThrows(BadRequestException.class,
        () -> new TransactionFilter(null, "x".repeat(101), null, null, null));
  }
}
