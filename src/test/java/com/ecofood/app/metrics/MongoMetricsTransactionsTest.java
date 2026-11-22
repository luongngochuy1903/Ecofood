package com.ecofood.app.metrics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.metrics.service.*;
import com.mongodb.*;
import com.mongodb.client.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

class MongoMetricsTransactionsTest {
  private final MongoClient client = mock(MongoClient.class);
  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final MongoTemplate scoped = mock(MongoTemplate.class);
  private final ClientSession session = mock(ClientSession.class);
  private final MongoMetricsTransactions transactions = new MongoMetricsTransactions(client, mongo);

  private Document replicaSet() {
    return new Document("setName", "rs").append("logicalSessionTimeoutMinutes", 30).append("maxWireVersion", 25);
  }

  @SuppressWarnings("unchecked")
  private void supported() {
    when(mongo.executeCommand(any(Document.class))).thenReturn(replicaSet());
    when(client.startSession()).thenReturn(session);
    when(mongo.withSession(session)).thenReturn(scoped);
    when(session.withTransaction(any(TransactionBody.class), any(TransactionOptions.class)))
        .thenAnswer(call -> ((TransactionBody<?>) call.getArgument(0)).execute());
  }

  @Test
  void usesSessionScopedOperationsSnapshotIsolationAndMajorityCommit() {
    supported();
    assertEquals("ok", transactions.execute(ops -> {
      assertSame(scoped, ops);
      return "ok";
    }));
    var options = org.mockito.ArgumentCaptor.forClass(TransactionOptions.class);
    verify(session).withTransaction(any(), options.capture());
    assertEquals(ReadConcern.SNAPSHOT, options.getValue().getReadConcern());
    assertEquals(WriteConcern.MAJORITY, options.getValue().getWriteConcern());
    assertEquals(ReadPreference.primary(), options.getValue().getReadPreference());
    verify(session).close();
  }

  @Test
  void failsClosedOnStandaloneOrUnknownTopologyWithoutStartingTransaction() {
    when(mongo.executeCommand(any(Document.class))).thenReturn(new Document("maxWireVersion", 25));
    var failure = assertThrows(KpiProcessingException.class, () -> transactions.execute(ops -> fail("must not write")));
    assertEquals("MONGO_TRANSACTIONS_UNSUPPORTED", failure.code());
    assertTrue(failure.retryable());
    verifyNoInteractions(client);
    assertTrue(MongoMetricsTransactions.supportsTransactions(replicaSet()));
    assertTrue(MongoMetricsTransactions.supportsTransactions(new Document("msg", "isdbgrid")
        .append("logicalSessionTimeoutMinutes", 30).append("maxWireVersion", 8)));
    assertFalse(MongoMetricsTransactions.supportsTransactions(new Document("msg", "isdbgrid")
        .append("logicalSessionTimeoutMinutes", 30).append("maxWireVersion", 7)));
  }

  @Test
  void retriesFirstBucketDuplicateRaceWithANewTransaction() {
    supported();
    var attempts = new AtomicInteger();
    assertEquals("ok", transactions.execute(ops -> {
      if (attempts.getAndIncrement() == 0) throw new DuplicateKeyException("bucket race");
      return "ok";
    }));
    assertEquals(2, attempts.get());
    verify(client, times(2)).startSession();
    verify(session, times(2)).close();
  }

  @Test
  void exposesTransientMongoLabelsToDriverInsteadOfHidingThemInSpringExceptions() {
    supported();
    var conflict = new MongoException(112, "write conflict");
    conflict.addLabel("TransientTransactionError");
    var failure = assertThrows(MongoException.class, () -> transactions.execute(ops -> {
      throw new DataAccessResourceFailureException("translated", conflict);
    }));
    assertSame(conflict, failure);
    assertTrue(failure.hasErrorLabel("TransientTransactionError"));
    verify(session).close();
  }
}
