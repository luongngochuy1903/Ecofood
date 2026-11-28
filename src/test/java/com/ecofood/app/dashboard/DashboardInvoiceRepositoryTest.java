package com.ecofood.app.dashboard;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.transaction.entity.Invoice;
import com.ecofood.app.transaction.repository.DashboardInvoiceRepository;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class DashboardInvoiceRepositoryTest {
  @Test void pairsEveryTenantWithItsOwnIdsAndPaginatesTheCombinedQuery() {
    var mongo = mock(MongoTemplate.class);
    Map<String,Set<String>> scopes = new LinkedHashMap<>();
    scopes.put("enterprise",null); scopes.put("regional",Set.of("invoice-1")); scopes.put("blocked",Set.of());
    var row = new Invoice(); row.setId("invoice-1"); row.setTenantId("regional");
    when(mongo.find(any(Query.class),eq(Invoice.class))).thenReturn(List.of(row));
    when(mongo.count(any(Query.class),eq(Invoice.class))).thenReturn(41L);
    var page = new DashboardInvoiceRepository(mongo).find(scopes,PageRequest.of(2,20,Sort.by(Sort.Direction.DESC,"receivedAt","id")));
    assertEquals(41,page.getTotalElements()); assertEquals(2,page.getNumber());
    verify(mongo).find(argThat(query -> {
      var branches = query.getQueryObject().getList("$or",Document.class);
      return branches.size() == 2 && branches.contains(new Document("tenantId","enterprise"))
          && branches.contains(new Document("tenantId","regional").append("id",new Document("$in",Set.of("invoice-1"))))
          && query.getSkip() == 40 && query.getLimit() == 20;
    }),eq(Invoice.class));
  }
  @Test void noAuthorizedIdsDoesNotReadInvoices() {
    var mongo = mock(MongoTemplate.class);
    assertTrue(new DashboardInvoiceRepository(mongo).find(Map.of("tenant",Set.of()),PageRequest.of(0,20)).isEmpty());
    verifyNoInteractions(mongo);
  }
}
