package com.ecofood.app.complaint;

import static org.junit.jupiter.api.Assertions.*;
import static com.ecofood.app.complaint.ComplaintFixture.*;
import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.config.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RoutingPolicyPublisherTest {
  final ComplaintFixture f = new ComplaintFixture();
  @Test void publicationIsExplicitInactiveAndDoesNotMutateDraftOrPersistAnything() {
    var draft = f.internal(); f.policies.add(draft);
    var published = f.publisher.publish(draft, f.employee("publisher", T));
    assertEquals(Publication.PUBLISHED, published.publication()); assertFalse(published.active());
    assertEquals(NOW, published.publishedAt()); assertEquals("publisher", published.publishedBy());
    assertEquals(Publication.DRAFT, draft.publication()); assertEquals(List.of(draft), f.policies);
    f.validator.validate(published);
  }
  @Test void explicitActivationAndRetirementKeepPolicyBodyAndVersionStable() {
    var published = f.publisher.publish(f.internal(), f.employee("publisher", T));
    var active = f.publisher.activate(published, f.employee("publisher", T)); assertTrue(active.active());
    var retired = f.publisher.retire(active, f.employee("publisher", T));
    assertEquals(Publication.RETIRED, retired.publication()); assertFalse(retired.active());
    assertEquals(active.version(), retired.version()); assertEquals(active.edges(), retired.edges());
    assertEquals(active.publishedAt(), retired.publishedAt());
    assertThrows(BadRequestException.class, () -> f.publisher.activate(retired, f.employee("publisher", T)));
  }
  @Test void customerWrongTenantAndMissingManagementPermissionsCannotPublish() {
    assertThrows(ForbiddenException.class, () -> f.publisher.publish(f.internal(), f.customer("customer")));
    assertThrows(ForbiddenException.class, () -> f.publisher.publish(f.internal(), f.employee("publisher", M)));
    var readOnly = new Actor(ActorType.EMPLOYEE, "publisher", T, Set.of(Permission.COMPLAINT_READ));
    assertThrows(ForbiddenException.class, () -> f.publisher.publish(f.internal(), readOnly));
    f.permissions.put(T + ":publisher", Set.of());
    assertThrows(ForbiddenException.class, () -> f.publisher.publish(f.internal(), f.employee("publisher", T)));
  }
  @Test void overlappingActivePoliciesCannotBeActivated() {
    var old = f.published(f.internal()); f.policies.add(old);
    var draft = f.internal();
    var next = new ComplaintRoutingPolicy(draft.id(), 2, T, draft.kind(), draft.categoryCodes(), draft.queueIds(), draft.edges(),
        draft.reporterOriginQueueIds(), draft.partnerBindings(), null, Publication.DRAFT, false, null, null);
    var published = f.publisher.publish(next, f.employee("publisher", T));
    assertThrows(BadRequestException.class, () -> f.publisher.activate(published, f.employee("publisher", T)));
    f.policies.set(0, f.publisher.retire(old, f.employee("publisher", T)));
    assertTrue(f.publisher.activate(published, f.employee("publisher", T)).active());
  }
  @Test void aPublishedVersionCannotBeOverwrittenOrRepublished() {
    f.policies.add(f.published(f.internal()));
    assertThrows(BadRequestException.class, () -> f.publisher.publish(f.internal(), f.employee("publisher", T)));
    assertThrows(BadRequestException.class, () -> f.publisher.publish(f.published(f.internal()), f.employee("publisher", T)));
    assertThrows(BadRequestException.class, () -> f.publisher.activate(f.internal(), f.employee("publisher", T)));
  }
  @Test void immutablePolicyCollectionsCannotBeChangedAfterValidation() {
    var draft = f.internal(); assertThrows(UnsupportedOperationException.class, () -> draft.queueIds().add("evil"));
    assertThrows(UnsupportedOperationException.class, () -> draft.edges().add(edge("review", "support")));
    var actor = f.employee("handler", T); assertThrows(UnsupportedOperationException.class, () -> actor.permissions().clear());
  }
}
