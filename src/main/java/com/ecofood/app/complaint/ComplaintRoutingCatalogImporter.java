package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintRoutingCatalog.Configuration;
import com.ecofood.app.config.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Component;

/** Optional operator-controlled import, not an HTTP management API or role-derived auto-seeding. */
@Component @RequiredArgsConstructor
public class ComplaintRoutingCatalogImporter implements ApplicationRunner {
  private final ComplaintMongoStore store;
  private final MongoComplaintRoutingDirectory directory;
  private final RoutingPolicyValidator validator;
  private final Clock clock;
  @Value("${ecofood.complaints.routing-file:}") private String file;
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  public void run(ApplicationArguments args) throws Exception {
    if (file == null || file.isBlank()) return;
    var path = Path.of(file).toAbsolutePath().normalize();
    if (Files.size(path) > 1_000_000) throw new BadRequestException("Complaint routing file is too large");
    install(mapper.readValue(Files.readString(path), Configuration.class));
  }
  public void install(Configuration config) {
    if (config == null || config.version() < 1) throw new BadRequestException("Invalid catalog version");
    if (config.queues().stream().anyMatch(q -> q.id() != null && q.id().startsWith(InvoiceAuthorityRoute.PREFIX))
        || config.policies().stream().anyMatch(p -> InvoiceAuthorityRoute.POLICY.equals(p.id())))
      throw new BadRequestException("Reserved Invoice authority routing identity");
    String hash;
    try { hash = ComplaintCommandIdentity.hash(canonical(mapper.valueToTree(config))); }
    catch (Exception ex) { throw new BadRequestException("Invalid routing catalog"); }
    var existing = store.template().findById("active", ComplaintRoutingCatalog.class);
    if (existing != null && existing.configurationVersion() == config.version() && existing.contentHash().equals(hash)) return;
    if (existing != null && existing.configurationVersion() >= config.version()) throw new BadRequestException("Routing catalog requires a newer version");
    directory.withConfiguration(config, () -> {
      var platform = directory.platformTenantId();
      if (!directory.isActiveEmployee(config.approvedBy(), platform) || !directory.hasPermission(config.approvedBy(), platform, Permission.COMPLAINT_ROUTING_MANAGE))
        throw new BadRequestException("Catalog requires an active Platform routing administrator");
      unique(config.queues().stream().map(ComplaintQueue::id).toList()); unique(config.memberships().stream().map(m -> m.queueId() + ":" + m.userId()).toList());
      unique(config.policies().stream().map(p -> p.id() + ":" + p.version()).toList());
      unique(config.grants().stream().map(g -> g.userId() + ":" + g.queueId()).toList());
      for (var policy : config.policies()) {
        validator.validate(policy);
        if (policy.publication() == Publication.DRAFT || !directory.isActiveEmployee(policy.publishedBy(), policy.ownerTenantId())
            || !directory.hasPermission(policy.publishedBy(), policy.ownerTenantId(), Permission.COMPLAINT_ROUTING_MANAGE)) throw new BadRequestException("Policy publication requires its owner administrator");
        if (policy.active()) for (String category : policy.categoryCodes()) {
          long count = config.policies().stream().filter(p -> p.active() && p.kind() == policy.kind() && p.ownerTenantId().equals(policy.ownerTenantId()) && p.categoryCodes().contains(category)).count();
          if (count != 1) throw new BadRequestException("Ambiguous active routing policy");
        }
      }
      for (var grant : config.grants()) {
        var queue = validator.requireQueue(grant.queueId());
        if (grant.kinds().isEmpty() || !directory.isActiveEmployee(grant.userId(), queue.scope().tenantId())
            || directory.memberships(queue.id()).stream().noneMatch(m -> m.active() && m.userId().equals(grant.userId()))) throw new BadRequestException("Invalid Complaint scope grant");
        grant.sourceTenantIds().forEach(ComplaintValidation::id); grant.invoiceIds().forEach(ComplaintValidation::id);
      }
      if (existing != null) for (var previous : existing.configuration().policies()) {
        var replacement = config.policies().stream().filter(p -> p.id().equals(previous.id()) && p.version() == previous.version()).findFirst()
            .orElseThrow(() -> new BadRequestException("Retain published policy versions for existing cases"));
        var immutablePrevious = new ComplaintRoutingPolicy(previous.id(), previous.version(), previous.ownerTenantId(), previous.kind(), previous.categoryCodes(), previous.queueIds(), previous.edges(), previous.reporterOriginQueueIds(), previous.partnerBindings(), previous.platformInitialQueueId(), Publication.PUBLISHED, false, previous.publishedAt(), previous.publishedBy());
        var immutableNext = new ComplaintRoutingPolicy(replacement.id(), replacement.version(), replacement.ownerTenantId(), replacement.kind(), replacement.categoryCodes(), replacement.queueIds(), replacement.edges(), replacement.reporterOriginQueueIds(), replacement.partnerBindings(), replacement.platformInitialQueueId(), Publication.PUBLISHED, false, replacement.publishedAt(), replacement.publishedBy());
        if (!immutablePrevious.equals(immutableNext) || previous.publication() == Publication.RETIRED && replacement.publication() != Publication.RETIRED) throw new BadRequestException("Published policy content is immutable");
      }
      return null;
    });
    var value = new ComplaintRoutingCatalog("active", config.version(), config.approvedBy(), hash, clock.instant(), config);
    if (existing == null) store.template().insert(value);
    else {
      var result = store.template().findAndModify(Query.query(Criteria.where("_id").is("active").and("configurationVersion").is(existing.configurationVersion())),
          new Update().set("configurationVersion", value.configurationVersion()).set("approvedBy", value.approvedBy()).set("contentHash", hash).set("importedAt", value.importedAt()).set("configuration", config),
          FindAndModifyOptions.options().returnNew(true), ComplaintRoutingCatalog.class);
      if (result == null) throw new IllegalStateException("Routing catalog changed concurrently");
    }
  }
  private void unique(List<String> keys) { if (keys.stream().anyMatch(Objects::isNull) || new HashSet<>(keys).size() != keys.size()) throw new BadRequestException("Duplicate routing configuration identity"); }
  // Configuration collections are sets of identities/edges; stable across JVM Set iteration orders.
  private String canonical(com.fasterxml.jackson.databind.JsonNode node) {
    if (node.isObject()) {
      var entries = new TreeMap<String, String>(); node.fields().forEachRemaining(e -> entries.put(e.getKey(), canonical(e.getValue())));
      return entries.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue()).collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    if (node.isArray()) { var values = new ArrayList<String>(); node.forEach(e -> values.add(canonical(e))); Collections.sort(values); return String.join(",", values).transform(s -> "[" + s + "]"); }
    return node.toString();
  }
}
