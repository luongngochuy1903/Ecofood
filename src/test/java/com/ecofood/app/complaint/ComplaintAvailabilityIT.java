package com.ecofood.app.complaint;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClients;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/** Opt-in read-only environment diagnosis. No identity, token, subject or description is printed. */
class ComplaintAvailabilityIT {
  @Test void routingConfigurationSummary() throws Exception {
    var config = new Properties();
    if (Files.exists(Path.of(".env"))) try (var reader = Files.newBufferedReader(Path.of(".env"))) { config.load(reader); }
    String uri = System.getenv("MONGODB_URI"); if (uri == null || uri.isBlank()) uri = config.getProperty("MONGODB_URI");
    if (uri == null || uri.isBlank()) throw new IllegalStateException("MongoDB URI required");
    String name = System.getenv("MONGODB_DATABASE"); if (name == null || name.isBlank()) name = config.getProperty("MONGODB_DATABASE", "appdb");
    var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
        .applyToClusterSettings(b -> b.serverSelectionTimeout(30, TimeUnit.SECONDS)).build();
    try (var client = MongoClients.create(settings)) {
      var row = client.getDatabase(name).getCollection("complaint_routing_catalog").find(new Document("_id", "active")).first();
      System.out.println("COMPLAINT_DIAGNOSTIC activeCatalog=" + (row != null));
      if (row == null) return;
      var catalog = row.get("configuration", Document.class);
      for (String key : List.of("queues", "memberships", "grants"))
        System.out.println("COMPLAINT_DIAGNOSTIC " + key + "=" + catalog.getList(key, Document.class, List.of()).size());
      for (var c : catalog.getList("categories", Document.class, List.of()))
        if (!"INTERNAL".equals(c.getString("kind"))) System.out.println("COMPLAINT_DIAGNOSTIC category=" + c.getString("kind") + ":" + c.getString("code") + " active=" + c.get("active"));
      for (var p : catalog.getList("policies", Document.class, List.of()))
        if (!"INTERNAL".equals(p.getString("kind"))) System.out.println("COMPLAINT_DIAGNOSTIC policyKind=" + p.getString("kind") + " publication=" + p.get("publication") + " active=" + p.get("active") + " categories=" + p.get("categoryCodes"));
    }
  }
}
