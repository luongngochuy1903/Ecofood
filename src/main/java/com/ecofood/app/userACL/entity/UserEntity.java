package com.ecofood.app.userACL.entity;

import com.ecofood.core.entity.BaseEntity;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Our own user record, written BEFORE mirroring into Keycloak — externalLoginId
 * is null until that mirror succeeds.
 *
 * partyRelationships/externalLoginId are shaped to match DCMP's real
 * Service_User User entity exactly (confirmed by reading that class
 * directly): a single top-level externalLoginId (DCMP has no list-of-
 * relations structure for it). Confirmed separately by reading DCMP's real
 * switch-tenant flow (Service_Auth's SwitchTenantAuthServiceImpl): a user has
 * exactly ONE Keycloak identity no matter how many tenants their
 * partyRelationships grant them roles in — "switching tenant" never creates
 * a second Keycloak login, it only re-mints DCMP's own JWT with a different
 * tenantId claim. This project's UserService mirrors into Keycloak once, for
 * the first tenant assignment only.
 *
 * NOT included here: a flat tenantIdentifier field — the user's tenant
 * link(s) live entirely in partyRelationships (there can be more than one),
 * so a separate single-tenant field would always be redundant with, and
 * could drift from, that list. Removed after confirming it was never
 * actually populated by anything in this codebase.
 *
 * userCategory is a SINGLE field, not a list — deliberately diverging from
 * DCMP's own List<UserCategory>. A user here is fundamentally one kind of
 * user (a tenant user or a customer), not several categories at once; this
 * is a per-user classification, not something that varies per tenant
 * assignment.
 *
 * createdAt/updatedAt/createdBy/updatedBy/deleted are inherited from
 * BaseEntity and auto-filled by Spring Data Mongo auditing.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "app_user")
public class UserEntity extends BaseEntity {

  @Indexed(unique = true)
  private String username;

  private String email;

  @Field("first_name")
  private String firstName;

  @Field("last_name")
  private String lastName;

  @Field("user_category")
  private UserCategory userCategory;

  @Field("party_to_party_relation")
  @Builder.Default
  private List<PartyToPartyRelationship> partyRelationships = new ArrayList<>();

  /** Keycloak's own internal user UUID — null until mirrored into Keycloak. */
  @Field("external_login_id")
  private String externalLoginId;
}
