package com.ecofood.app.userACL.entity;

import com.ecofood.core.entity.BaseEntity;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_SecurityManagement GroupEntity concept
 * (confirmed by reading that class directly) — a child of a Group
 * (groupIdentifier FK), holding which specific records an Access Control
 * Group's accessEntityType is scoped to when its Group.accessLevel is
 * LIMITED_ACCESS. One GroupEntity row per (group, entity type) pair.
 *
 * Simplified from DCMP's real model: DCMP embeds polymorphic entity objects
 * (StandardEntity/ProductEntity, the latter also carrying an insurerId) to
 * support multiple real-world entity shapes; this project has no such
 * per-entity-type extra fields yet, so entityIds is a flat id list rather
 * than an embedded-object list.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "group_entity")
public class GroupEntity extends BaseEntity {

  @Field("group_identifier")
  @Indexed
  private ObjectId groupIdentifier;

  @Field("access_entity_type")
  private AccessEntityTypeEnum accessEntityType;

  @Field("entities")
  @Builder.Default
  private List<ObjectId> entityIds = new ArrayList<>();
}
