package com.ecofood.app.userACL.entity;

import com.ecofood.app.common.entity.BaseEntity;
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
 * Matches DCMP's real Service_SecurityManagement GroupRole entity (confirmed
 * by reading that class directly) — the Group-to-Role junction. name/code
 * are the owning Group's own name/code, denormalized onto this doc; roles is
 * a plain list of Role ids (NOT embedded Role objects, unlike UserRole).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "group_role")
public class GroupRole extends BaseEntity {

  private String name;

  private String code;

  @Field("group_identifier")
  @Indexed
  private ObjectId groupId;

  @Field("roles")
  @Builder.Default
  private List<ObjectId> roles = new ArrayList<>();
}
