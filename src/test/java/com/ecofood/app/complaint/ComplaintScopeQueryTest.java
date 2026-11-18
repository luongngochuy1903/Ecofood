package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ecofood.app.complaint.ComplaintCoreFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.config.BadRequestException;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;

class ComplaintScopeQueryTest {
  final MongoTemplate mongo = mock(MongoTemplate.class);
  final ComplaintRepository repository = new ComplaintRepository(new ComplaintMongoStore(mongo));
  @Test void reporterScopeBindsTypeIdentityAndHomeTenant() {
    var query = repository.scoped(new ReporterScope(ActorType.CUSTOMER, CUSTOMER, PORTAL)).getQueryObject();
    assertEquals(CUSTOMER, query.getString("reporterUserId")); assertEquals(PORTAL, query.getString("reporterHomeTenantId")); assertEquals(ActorType.CUSTOMER, query.get("reporterType"));
    assertThrows(BadRequestException.class, () -> repository.scoped(new ReporterScope(ActorType.GUEST, null, null)));
  }
  @Test void queueScopeAlwaysBindsBoundaryTenantQueuesAndLimitedCaseIds() {
    var query = repository.scoped(new QueueScope(new ProcessingScope(Boundary.TENANT, SELLER), Set.of("review"), false, Set.of(CASE))).getQueryObject();
    assertEquals(SELLER, query.getString("processingTenantId")); assertEquals(Boundary.TENANT, query.get("processingBoundary"));
    assertTrue(query.containsKey("currentQueueId")); assertTrue(query.containsKey("_id"));
  }
  @Test void emptyScopesMeanNoRowsAndNeverExpandToAllTenants() {
    var query = repository.scoped(new QueueScope(new ProcessingScope(Boundary.TENANT, SELLER), Set.of(), false, Set.of())).getQueryObject();
    assertTrue(((Collection<?>) query.get("currentQueueId", Document.class).get("$in")).isEmpty());
    assertTrue(((Collection<?>) query.get("_id", Document.class).get("$in")).isEmpty());
    assertThrows(BadRequestException.class, () -> repository.scoped(null));
  }
  @Test void unsafeSortingAndInvalidDatesAreRejectedBeforeDatabaseQueries() {
    var scope = new ReporterScope(ActorType.CUSTOMER, CUSTOMER, PORTAL);
    assertThrows(BadRequestException.class, () -> repository.query(scope, Filter.all(), PageRequest.of(0, 10, Sort.by("pendingCommit.event.reason"))));
    var now = java.time.Instant.now(); assertThrows(BadRequestException.class, () -> repository.query(scope, new Filter(Set.of(), Set.of(), now, now.minusSeconds(1)), PageRequest.of(0, 10)));
    verifyNoInteractions(mongo);
  }
}
