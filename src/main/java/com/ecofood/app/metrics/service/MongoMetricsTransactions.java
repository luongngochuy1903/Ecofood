package com.ecofood.app.metrics.service;

import com.mongodb.*;
import com.mongodb.client.MongoClient;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MongoMetricsTransactions {
  private final MongoClient client;
  private final MongoTemplate mongo;
  private volatile long supportedUntil;
  private static final TransactionOptions OPTIONS = TransactionOptions.builder()
      .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY)
      .readPreference(ReadPreference.primary()).build();

  public <T> T execute(Function<MongoOperations, T> action) {
    requireSupported();
    for (int attempt = 0; ; attempt++) {
      try (var session = client.startSession()) {
        // Driver retries TransientTransactionError and UnknownTransactionCommitResult correctly.
        return session.withTransaction(() -> {
          try { return action.apply(mongo.withSession(session)); }
          catch (DataAccessException translated) {
            // Preserve retry labels that Spring's exception translation would otherwise hide.
            for (Throwable cause = translated.getCause(); cause != null; cause = cause.getCause()) {
              if (cause instanceof MongoException failure
                  && failure.hasErrorLabel("TransientTransactionError")) throw failure;
            }
            throw translated;
          }
        }, OPTIONS);
      } catch (DuplicateKeyException ex) {
        // A concurrent first writer/rebuild may create the same bucket or source event.
        if (attempt >= 2) throw ex;
      } catch (MongoException ex) {
        if (ex.getCode() != 11000 || attempt >= 2) throw ex;
      }
    }
  }

  private void requireSupported() {
    long confirmedUntil = supportedUntil;
    if (confirmedUntil != 0 && System.nanoTime() - confirmedUntil < 0) return;
    final Document hello;
    try { hello = mongo.executeCommand(new Document("hello", 1)); }
    catch (RuntimeException ex) {
      throw new KpiProcessingException("MONGO_TRANSACTION_CAPABILITY_UNVERIFIED", true);
    }
    if (!supportsTransactions(hello))
      throw new KpiProcessingException("MONGO_TRANSACTIONS_UNSUPPORTED", true);
    supportedUntil = System.nanoTime() + 30_000_000_000L;
  }

  public static boolean supportsTransactions(Document hello) {
    if (hello == null || hello.get("logicalSessionTimeoutMinutes") == null) return false;
    int wire = hello.get("maxWireVersion") instanceof Number n ? n.intValue() : 0;
    return hello.get("setName") != null && wire >= 7
        || "isdbgrid".equals(hello.getString("msg")) && wire >= 8;
  }
}
