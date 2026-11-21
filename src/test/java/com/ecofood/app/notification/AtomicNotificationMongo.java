package com.ecofood.app.notification;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ecofood.app.notification.entity.Notification;
import com.mongodb.client.result.UpdateResult;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.*;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.*;
import org.springframework.data.mongodb.core.query.*;

/** Unit simulation of atomic document operations, not a substitute for a real MongoDB integration test. */
class AtomicNotificationMongo {
  final MongoTemplate mongo = mock(MongoTemplate.class);
  final MappingMongoConverter converter;
  private final MongoMappingContext context;
  final Map<String, Map<Object, Document>> collections = new HashMap<>();
  AtomicNotificationMongo() throws Exception {
    var conversions = new com.ecofood.app.proposition.service.PropositionMongoConfiguration().propositionMongoConversions();
    context = new MongoMappingContext(); context.setSimpleTypeHolder(conversions.getSimpleTypeHolder()); context.afterPropertiesSet();
    converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context); converter.setCustomConversions(conversions); converter.afterPropertiesSet();
    doAnswer(a -> modify(a.getArgument(0), a.getArgument(1), a.getArgument(3))).when(mongo).findAndModify(any(Query.class), any(UpdateDefinition.class), any(FindAndModifyOptions.class), any(Class.class));
    doAnswer(a -> update(a.getArgument(0), a.getArgument(1), a.getArgument(2), false)).when(mongo).updateFirst(any(Query.class), any(UpdateDefinition.class), any(Class.class));
    doAnswer(a -> update(a.getArgument(0), a.getArgument(1), a.getArgument(2), true)).when(mongo).updateMulti(any(Query.class), any(UpdateDefinition.class), any(Class.class));
    doAnswer(a -> insert(a.getArgument(0))).when(mongo).insert(any(Notification.class));
    doAnswer(a -> find(a.getArgument(0), a.getArgument(1)).stream().findFirst().orElse(null)).when(mongo).findOne(any(Query.class), any(Class.class));
    doAnswer(a -> find(a.getArgument(0), a.getArgument(1))).when(mongo).find(any(Query.class), any(Class.class));
    doAnswer(a -> !find(a.getArgument(0), a.getArgument(1)).isEmpty()).when(mongo).exists(any(Query.class), any(Class.class));
    doAnswer(a -> find(Query.query(Criteria.where("_id").is(a.getArgument(0))), a.getArgument(1)).stream().findFirst().orElse(null)).when(mongo).findById(any(), any(Class.class));
  }
  String name(Class<?> type) { return context.getRequiredPersistentEntity(type).getCollection(); }
  synchronized Map<Object, Document> rows(Class<?> type) { return collections.computeIfAbsent(name(type), ignored -> new LinkedHashMap<>()); }
  synchronized <T> T insert(T object) {
    Document doc = new Document(); converter.write(object, doc);
    if (rows(object.getClass()).putIfAbsent(doc.get("_id"), doc) != null) throw new DuplicateKeyException("duplicate"); return object;
  }
  Document query(Query q, Class<?> type) { return new QueryMapper(converter).getMappedObject(q.getQueryObject(), context.getRequiredPersistentEntity(type)); }
  synchronized <T> List<T> find(Query q, Class<T> type) {
    Document filter = query(q, type); return rows(type).values().stream().filter(d -> matches(d, filter)).limit(q.getLimit() > 0 ? q.getLimit() : Integer.MAX_VALUE).map(d -> converter.read(type, d)).toList();
  }
  synchronized <T> T modify(Query q, UpdateDefinition update, Class<T> type) {
    Document filter = query(q, type);
    for (Document doc : rows(type).values()) if (matches(doc, filter)) { apply(doc, update, type); return converter.read(type, doc); } return null;
  }
  synchronized UpdateResult update(Query q, UpdateDefinition update, Class<?> type, boolean multi) {
    long count = 0; Document filter = query(q, type);
    for (Document doc : rows(type).values()) if (matches(doc, filter)) { apply(doc, update, type); count++; if (!multi) break; }
    return UpdateResult.acknowledged(count, count, null);
  }
  void apply(Document doc, UpdateDefinition update, Class<?> type) {
    Document mapped = new UpdateMapper(converter).getMappedObject(update.getUpdateObject(), context.getRequiredPersistentEntity(type));
    for (var operation : mapped.entrySet()) for (var item : ((Document) operation.getValue()).entrySet()) {
      String path = item.getKey(); Object value = item.getValue();
      switch (operation.getKey()) {
        case "$set" -> put(doc, path, value, false);
        case "$unset" -> put(doc, path, null, true);
        case "$inc" -> put(doc, path, ((Number) Optional.ofNullable(get(doc, path)).orElse(0)).intValue() + ((Number) value).intValue(), false);
        default -> throw new UnsupportedOperationException(operation.getKey());
      }
    }
  }
  @SuppressWarnings("unchecked") static Object get(Map<String, Object> doc, String path) {
    Object result = doc; for (String segment : path.split("\\.")) { if (!(result instanceof Map)) return null; result = ((Map<String, Object>) result).get(segment); } return result;
  }
  @SuppressWarnings("unchecked") static void put(Map<String, Object> doc, String path, Object value, boolean remove) {
    String[] parts = path.split("\\."); Map<String, Object> target = doc;
    for (int i = 0; i < parts.length - 1; i++) target = (Map<String, Object>) target.computeIfAbsent(parts[i], ignored -> new Document());
    if (remove) target.remove(parts[parts.length - 1]); else target.put(parts[parts.length - 1], value);
  }
  @SuppressWarnings("unchecked") static boolean matches(Document doc, Document filter) {
    for (var field : filter.entrySet()) {
      if (field.getKey().equals("$and")) { if (!((List<Document>) field.getValue()).stream().allMatch(f -> matches(doc, f))) return false; continue; }
      if (field.getKey().equals("$or")) { if (!((List<Document>) field.getValue()).stream().anyMatch(f -> matches(doc, f))) return false; continue; }
      Object actual = get(doc, field.getKey()), expected = field.getValue();
      if (expected instanceof Document conditions) {
        for (var condition : conditions.entrySet()) {
          Object rhs = condition.getValue(); boolean accepted = switch (condition.getKey()) {
            case "$exists" -> (actual != null) == (boolean) rhs;
            case "$in" -> ((List<?>) rhs).contains(actual);
            case "$lt" -> actual != null && compare(actual, rhs) < 0;
            case "$gt" -> actual != null && compare(actual, rhs) > 0;
            case "$lte" -> actual != null && compare(actual, rhs) <= 0;
            case "$ne" -> !Objects.equals(actual, rhs);
            default -> throw new UnsupportedOperationException(condition.getKey());
          }; if (!accepted) return false;
        }
      } else if (!Objects.equals(actual, expected)) return false;
    } return true;
  }
  @SuppressWarnings({"unchecked", "rawtypes"}) static int compare(Object left, Object right) { return ((Comparable) left).compareTo(right); }
  synchronized Document raw(Class<?> type, Object id) { return rows(type).get(id); }
}
