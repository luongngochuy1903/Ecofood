package com.ecofood.app.common.entity;

import java.time.Instant;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_Core MongoBaseEntity shape exactly — the
 * ObjectId id field, field names, and the
 * @CreatedDate/@LastModifiedDate/@CreatedBy/@LastModifiedBy annotations
 * (confirmed by reading that class directly) — auto-filled by Spring Data
 * MongoDB's auditing support (see MongoAuditingConfig's AuditorAware bean +
 * @EnableMongoAuditing on AppApplication), not manually set by callers.
 *
 * createdBy/updatedBy stay String (sourced from a JWT subject claim via
 * MongoAuditingConfig's AuditorAware<String>), not ObjectId — DCMP's
 * AuditorAware<ObjectId> reads a parsed JWT claim directly into an ObjectId,
 * but a String is the simpler, equally correct source type here.
 *
 * getIdAsString() is a convenience bridge for the many call sites across
 * this codebase (JSON responses, Keycloak attribute maps, JWT claims) that
 * need the id in hex-string form rather than as a raw ObjectId.
 */
public class BaseEntity {

  public static final String FIELD_DELETED = "deleted";

  @Id
  private ObjectId id;

  @CreatedDate
  @Field("created_time_stamp")
  private Instant createdAt;

  @LastModifiedDate
  @Field("updated_time_stamp")
  private Instant updatedAt;

  @CreatedBy
  @Field("created_by_user_identifier")
  private String createdBy;

  @LastModifiedBy
  @Field("updated_by_user_identifier")
  private String updatedBy;

  @Field(FIELD_DELETED)
  private Boolean deleted = false;

  public ObjectId getId() {
    return id;
  }

  public void setId(ObjectId id) {
    this.id = id;
  }

  public String getIdAsString() {
    return id != null ? id.toHexString() : null;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public Boolean getDeleted() {
    return deleted;
  }

  public void setDeleted(Boolean deleted) {
    this.deleted = deleted;
  }
}
