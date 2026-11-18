package com.ecofood.app.migration;

import com.mongodb.*;
import com.mongodb.client.*;
import com.mongodb.client.model.Filters;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.*;
import org.junit.jupiter.api.Test;

/** Opt-in maintenance only. Feedback/Notification sources are never changed or dropped. */
public class FeedbackRetirementMigrationIT {
  static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
  @Test void inventoryBackupAndArchive() throws Exception {
    boolean apply = Boolean.getBoolean("feedback.migration.apply");
    var config = new Properties();
    if (Files.exists(Path.of(".env"))) try (var reader = Files.newBufferedReader(Path.of(".env"))) { config.load(reader); }
    String uri = System.getenv("MONGODB_URI"); if (uri == null || uri.isBlank()) uri = config.getProperty("MONGODB_URI");
    if (uri == null || uri.isBlank()) throw new IllegalStateException("MongoDB URI required");
    String dbName = System.getenv("MONGODB_DATABASE"); if (dbName == null || dbName.isBlank()) dbName = config.getProperty("MONGODB_DATABASE", "appdb");
    Path dir = Path.of("migration-backups", "feedback-retirement", Instant.now().toEpochMilli() + "-" + UUID.randomUUID()); Files.createDirectories(dir);
    var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
        .applyToClusterSettings(b -> b.serverSelectionTimeout(10, TimeUnit.SECONDS)).build();
    try (var client = MongoClients.create(settings)) {
      var db = client.getDatabase(dbName).withReadConcern(ReadConcern.MAJORITY).withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
      var report = new Document("mode", apply ? "ARCHIVE" : "DRY_RUN").append("createdAt", Date.from(Instant.now()));
      var results = new ArrayList<Document>();
      results.add(archive(db, "service_feedback", new Document(), "service_feedback_archive_v1", dir, apply));
      results.add(archive(db, "notification", Filters.eq("reference_type", "FEEDBACK"), "notification_feedback_archive_v1", dir, apply));
      report.append("collections", results).append("feedbackSourcesDeleted", false)
          .append("legacyPermissionCount", db.getCollection("permission").countDocuments(Filters.regex("permission_code", "(?i)feedback")));
      var codes = db.getCollection("permission").find(Filters.regex("permission_code", "(?i)feedback")).projection(new Document("permission_code", 1)).into(new ArrayList<>()).stream().map(d -> d.getString("permission_code")).toList();
      report.append("legacyPermissionCodes", codes);
      System.out.println("FEEDBACK_RETIREMENT permissionCodes=" + codes);
      if (Boolean.getBoolean("feedback.migration.retire-permissions")) {
        var permissionFilter = Filters.eq("permission_code", "FEEDBACK_READ");
        var ids = new ArrayList<Object>();
        for (var permission : db.getCollection("permission").find(permissionFilter)) ids.add(permission.get("_id"));
        for (var old : db.getCollection("permission_feedback_archive_v1").find(Filters.eq("sourceDocument.permission_code", "FEEDBACK_READ"))) ids.add(old.get("_id"));
        var grantMatch = Filters.or(Filters.eq("code", "FEEDBACK_READ"), Filters.in("id", ids));
        var roleFilter = Filters.elemMatch("permissions", grantMatch);
        results.add(archive(db, "permission", permissionFilter, "permission_feedback_archive_v1", dir, apply));
        results.add(archive(db, "role_permission", roleFilter, "role_permission_feedback_archive_v1", dir, apply));
        if (apply) {
          for (var original : db.getCollection("role_permission").find(roleFilter)) {
            requireArchived(db, "role_permission_feedback_archive_v1", original);
            var permissions = original.getList("permissions", Document.class);
            var retained = permissions.stream().filter(p -> !"FEEDBACK_READ".equals(p.getString("code")) && !ids.contains(p.get("id"))).toList();
            var expected = Document.parse(original.toJson(JSON)); expected.put("permissions", retained);
            var result = db.getCollection("role_permission").updateOne(original, new Document("$set", new Document("permissions", retained)));
            if (result.getMatchedCount() != 1 || !hash(expected.toJson(JSON)).equals(hash(db.getCollection("role_permission").find(Filters.eq("_id", original.get("_id"))).first().toJson(JSON))))
              throw new IllegalStateException("ACL changed concurrently; stop and investigate");
          }
          if (db.getCollection("role_permission").countDocuments(roleFilter) != 0) throw new IllegalStateException("Legacy grants remain");
          for (var original : db.getCollection("permission").find(permissionFilter)) {
            requireArchived(db, "permission_feedback_archive_v1", original);
            if (db.getCollection("permission").deleteOne(original).getDeletedCount() != 1) throw new IllegalStateException("Permission changed concurrently");
          }
          if (db.getCollection("permission").countDocuments(permissionFilter) != 0) throw new IllegalStateException("Legacy permission remains");
          report.append("permissionsRetired", true);
          System.out.println("FEEDBACK_RETIREMENT obsolete FEEDBACK_READ catalog and grants retired; other grants preserved");
        }
      }
      Files.writeString(dir.resolve("manifest.json"), report.toJson(JSON), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      for (var row : results) System.out.println("FEEDBACK_RETIREMENT " + row.getString("source") + " count=" + row.get("sourceCount") + " verified=" + row.get("verified"));
      System.out.println("FEEDBACK_RETIREMENT report=" + dir + " mode=" + report.getString("mode") + " Feedback/Notification sources retained; no collection dropped");
    }
  }

  static Document archive(MongoDatabase db, String sourceName, Bson filter, String archiveName, Path dir, boolean apply) throws Exception {
    var targets = Map.of("service_feedback", "service_feedback_archive_v1", "notification", "notification_feedback_archive_v1",
        "permission", "permission_feedback_archive_v1", "role_permission", "role_permission_feedback_archive_v1");
    if (!archiveName.equals(targets.get(sourceName))) throw new SecurityException("Invalid migration source");
    var source = db.getCollection(sourceName); long count = source.countDocuments(filter);
    var report = new Document("source", sourceName).append("archive", archiveName).append("sourceCount", count)
        .append("indexes", db.listCollectionNames().into(new ArrayList<>()).contains(sourceName) ? source.listIndexes().into(new ArrayList<>()) : List.of()).append("verified", false);
    if (!apply || count == 0) return report.append("verified", count == 0).append("skipped", count == 0);
    var backup = dir.resolve(sourceName + ".ejsonl"); var digest = MessageDigest.getInstance("SHA-256"); long backedUp = 0;
    // Complete and close the local BSON-preserving backup BEFORE writing any archive document.
    try (var writer = Files.newBufferedWriter(backup, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
      for (var row : source.find(filter).sort(new Document("_id", 1))) {
        String json = row.toJson(JSON); writer.write(json); writer.newLine(); digest.update((hash(json) + "\n").getBytes(StandardCharsets.UTF_8)); backedUp++;
      }
    }
    if (backedUp != count) throw new IllegalStateException("Source changed during backup; quiesce writes and retry");
    String backupDigest = HexFormat.of().formatHex(digest.digest());
    var archive = db.getCollection(archiveName); long archived = 0;
    try (var reader = Files.newBufferedReader(backup, StandardCharsets.UTF_8)) {
      String line; while ((line = reader.readLine()) != null) {
        var original = Document.parse(line); String fingerprint = hash(original.toJson(JSON));
        var saved = archive.find(Filters.eq("_id", original.get("_id"))).first();
        if (saved == null) {
          var wrapper = new Document("_id", original.get("_id")).append("sourceCollection", sourceName).append("sourceDocument", original)
              .append("sha256", fingerprint).append("archivedAt", Date.from(Instant.now())).append("migrationVersion", 1);
          try { archive.insertOne(wrapper); }
          catch (MongoWriteException ex) { if (ex.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) throw ex; }
          saved = archive.find(Filters.eq("_id", original.get("_id"))).first();
        }
        if (saved == null || !fingerprint.equals(saved.getString("sha256"))
            || !fingerprint.equals(hash(saved.get("sourceDocument", Document.class).toJson(JSON)))) throw new IllegalStateException("Archive conflict; preserve both sources and investigate");
        archived++;
      }
    }
    var current = MessageDigest.getInstance("SHA-256"); long verified = 0;
    for (var row : source.find(filter).sort(new Document("_id", 1))) { current.update((hash(row.toJson(JSON)) + "\n").getBytes(StandardCharsets.UTF_8)); verified++; }
    if (verified != backedUp || archived != backedUp || !backupDigest.equals(HexFormat.of().formatHex(current.digest())))
      throw new IllegalStateException("Source changed during archive; no deletion allowed");
    return report.append("backupFile", backup.getFileName().toString()).append("backupContentDigest", backupDigest)
        .append("archivedVerified", archived).append("archiveTotal", archive.countDocuments()).append("verified", true);
  }
  static String hash(String json) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
  }
  static void requireArchived(MongoDatabase db, String collection, Document original) throws Exception {
    var saved = db.getCollection(collection).find(Filters.eq("_id", original.get("_id"))).first();
    if (saved == null || !hash(original.toJson(JSON)).equals(saved.getString("sha256"))
        || !hash(original.toJson(JSON)).equals(hash(saved.get("sourceDocument", Document.class).toJson(JSON))))
      throw new IllegalStateException("Backup required before retiring ACL metadata");
  }
}
