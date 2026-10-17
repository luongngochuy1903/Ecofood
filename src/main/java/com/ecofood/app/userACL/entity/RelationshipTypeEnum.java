package com.ecofood.app.userACL.entity;

/**
 * The discriminator for a PartyToPartyRelationship — shared by both
 * user-to-tenant relations (TENANT/INSURER/PREVENTION/THIRD_PARTY, matching
 * DCMP's real Service_Core UserRelationshipEnum constants) and
 * tenant-to-tenant relations (MASTER_TENANT/SUB_TENANT/BELONGTO/OWN),
 * mirroring DCMP's real model where Party (the common base of both User and
 * Tenant) carries one generic partyRelationships list discriminated by one
 * shared Service_Core RelationshipTypeEnum.
 *
 * MASTER_TENANT/SUB_TENANT codes and description wording ("MT"/"ST",
 * "Belongs to master tenant %s"/"Having sub-tenant %s") are confirmed
 * against that real enum directly — note the direction is from the
 * SUBJECT's point of view (a tenant with code=MT belongs TO a master, it is
 * not itself the master). BELONGTO("BL")/OWN("OW") codes are also real, but
 * DCMP's real descriptions for those two are empty strings; the
 * descriptions here are this project's own wording, not sourced DCMP text.
 *
 * Note "CUSTOMER" is NOT a value here; that's a UserCategoryEnum concept
 * instead (confirmed by reading both real enums directly).
 */
public enum RelationshipTypeEnum {
  TENANT("TENANT", "Tenant relationship"),
  ENTERPRISE("ENTERPRISE", "Enterprise relationship"),
  MASTER_TENANT("MT", "Belongs to master tenant %s"),
  SUB_TENANT("ST", "Having sub-tenant %s"),
  BELONGTO("BL", "Belongs to %s"),
  OWN("OW", "Owns tenant %s");

  private final String code;
  private final String description;

  RelationshipTypeEnum(String code, String description) {
    this.code = code;
    this.description = description;
  }

  public String getCode() {
    return code;
  }

  public String getDescription() {
    return description;
  }
}
