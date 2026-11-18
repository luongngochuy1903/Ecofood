package com.ecofood.app.notification;

import static org.mockito.Mockito.*;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.*;
import com.ecofood.app.notification.*;
import com.ecofood.app.notification.NotificationContracts.*;
import com.ecofood.app.customer.auth.*;
import com.ecofood.app.customer.repository.*;
import com.ecofood.app.userACL.entity.*;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
public class NotificationMongoPersistenceIT {
  @org.junit.jupiter.api.Test void persistsNotificationAndPreferencesInIsolatedCollections() throws Exception { main(new String[0]); }
  static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) throws Exception {
    Properties properties = new Properties();
    try (var reader = Files.newBufferedReader(Path.of(".env"))) { properties.load(reader); }
    String uri = System.getenv("MONGODB_URI"); if (uri == null || uri.isBlank()) uri = properties.getProperty("MONGODB_URI");
    if (uri == null || uri.isBlank()) throw new IllegalStateException("Mongo URI not configured");
    String database = System.getenv("MONGODB_DATABASE"); if (database == null || database.isBlank()) database = properties.getProperty("MONGODB_DATABASE", "appdb");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String noteCollection = "notification_test_" + suffix, prefCollection = "notification_preferences_test_" + suffix;
    var settings = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri))
        .applyToClusterSettings(builder -> builder.serverSelectionTimeout(10, TimeUnit.SECONDS)).build();
    try (var client = MongoClients.create(settings)) {
      var actualDb = client.getDatabase(database);
      var collectionNames = actualDb.listCollectionNames().into(new ArrayList<String>());
      check(!collectionNames.contains(noteCollection) && !collectionNames.contains(prefCollection), "Test collections already exist");
      try {
        var isolatedDb = (MongoDatabase) java.lang.reflect.Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(), new Class<?>[]{MongoDatabase.class}, (proxy, method, values) -> {
          if (method.getName().equals("drop")) throw new SecurityException("Database drop is forbidden");
          if (method.getName().equals("getCollection")) {
            Object[] mapped = values.clone(); String requested = (String) mapped[0];
            mapped[0] = requested.equals("notification") ? noteCollection : requested.equals("notification_preferences") ? prefCollection : null;
            if (mapped[0] == null) throw new SecurityException("Unexpected test collection");
            return method.invoke(actualDb, mapped);
          }
          return method.invoke(actualDb, values);
        });
        var baseFactory = new org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory(client, database);
        var isolatedFactory = (org.springframework.data.mongodb.MongoDatabaseFactory) java.lang.reflect.Proxy.newProxyInstance(
            org.springframework.data.mongodb.MongoDatabaseFactory.class.getClassLoader(), new Class<?>[]{org.springframework.data.mongodb.MongoDatabaseFactory.class}, (proxy, method, values) -> {
              if (method.getName().equals("getMongoDatabase")) return isolatedDb;
              return method.invoke(baseFactory, values);
            });
        var mongo = new MongoTemplate(isolatedFactory);
        new NotificationMongoConfig().notificationIndexes(mongo).run(null);
        var factory = new MongoRepositoryFactory(mongo);
        var notes = factory.getRepository(NotificationRepository.class);
        var prefs = factory.getRepository(NotificationPreferenceRepository.class);
        var identities = mock(CustomerIdentityService.class); var portal = mock(CustomerTenantResolver.class);
        String customer = new ObjectId().toHexString(), portalId = new ObjectId().toHexString();
        var user = UserEntity.builder().partyRelationships(List.of(PartyToPartyRelationship.builder().toPartyId(new ObjectId(portalId)).build())).build(); user.setId(new ObjectId(customer));
        when(identities.requireAccount(customer)).thenReturn(user);
        when(identities.requireSelfServiceAccount("CUSTOMER_NOTIFICATION_MANAGE")).thenReturn(user);
        when(identities.requirePurchaseSession()).thenReturn(new CustomerIdentityService.CustomerSession(customer, portalId));
        when(portal.resolve()).thenReturn(new CustomerTenantResolver.Portal(portalId, "Test Portal", "test", "test-client", "https://identity.example.test/realms/test"));
        var clock = Clock.systemUTC(); var preferences = new NotificationPreferenceService(identities, prefs, clock);
        var service = new NotificationService(identities, portal, mock(CustomerPurchaseRepository.class), preferences, notes, mongo, clock, mock(com.ecofood.app.complaint.ComplaintNotificationSource.class));
        var event = new Event("account-test-1", customer, portalId, null, Type.ACCOUNT, ReferenceType.ACCOUNT, customer, Channel.IN_APP);
        var created = service.create(event);
        check(notes.count() == 1, "Notification not persisted");
        check(service.create(event).duplicate() && notes.count() == 1, "Event retry duplicated notification");
        check(service.list(null, null, 0, 10).getContent().size() == 1, "Inbox mapping failed");
        check(service.unreadCount(null).unreadCount() == 1, "Unread count incorrect");
        var read = service.markRead(created.notificationId(), true);
        check(read.isRead() && read.readAt() != null && read.deliveryStatus() == DeliveryStatus.AVAILABLE, "Read status altered delivery state");
        check(service.unreadCount(null).unreadCount() == 0, "Read state not persisted");
        service.markRead(created.notificationId(), false);
        check(service.readAll().modifiedCount() == 1, "Read all failed");
        check(!preferences.getMine().marketingConsent(), "Marketing default must be opt-out");
        preferences.updateMine(new PreferenceRequest(null, null, null, true, true));
        check(prefs.count() == 1 && preferences.getMine().marketingEmailEnabled(), "Consent grant not persisted");
        preferences.updateMine(new PreferenceRequest(null, null, null, null, false));
        check(!preferences.getMine().marketingConsent() && preferences.getMine().marketingConsentWithdrawnAt() != null, "Withdrawal not persisted");
        var persistedPrefs = prefs.findByCustomerIdAndCustomerTenantIdAndDeletedNot(customer, portalId, true).orElseThrow();
        check(persistedPrefs.getConsentHistory().size() == 2 && persistedPrefs.getVersion() >= 1, "Consent audit/version not persisted");
        var email = service.create(new Event("account-test-1", customer, portalId, null, Type.ACCOUNT, ReferenceType.ACCOUNT, customer, Channel.EMAIL));
        check(email.deliveryStatus() == DeliveryStatus.PENDING && notes.count() == 2, "Email must remain pending");
        check(service.unreadCount(null).unreadCount() == 0, "Email queue leaked into in-app unread count");
        System.out.println("NOTIFICATION_MONGO_SMOKE PASS: persisted notification, dedup, inbox, read/unread/read-all, consent audit/version and pending email");
      } finally {
        check(noteCollection.matches("notification_test_[a-f0-9]{32}") && prefCollection.matches("notification_preferences_test_[a-f0-9]{32}"), "Unsafe cleanup target");
        var currentNames = actualDb.listCollectionNames().into(new ArrayList<String>());
        if (currentNames.contains(noteCollection)) actualDb.getCollection(noteCollection).drop();
        if (currentNames.contains(prefCollection)) actualDb.getCollection(prefCollection).drop();
        System.out.println("NOTIFICATION_MONGO_SMOKE CLEANUP: isolated UUID test collections removed");
      }
    } catch (Exception error) {
      System.out.println("NOTIFICATION_MONGO_SMOKE FAIL: " + error.getClass().getSimpleName()); throw error;
    }
  }
}
