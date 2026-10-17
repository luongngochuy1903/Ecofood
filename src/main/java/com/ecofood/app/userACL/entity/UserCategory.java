package com.ecofood.app.userACL.entity;

import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;

/**
 * Matches DCMP's real Service_User UserCategory field names and types
 * (confirmed by reading that class directly) — an embeddable POJO, not an
 * enum. Unlike DCMP (where User.userCategory is a List<UserCategory>), this
 * project stores a SINGLE UserCategory per user on UserEntity — a user here
 * is fundamentally one kind of user (a tenant user or a customer), not
 * several categories at once.
 *
 * equals/hashCode are hand-written (code+subCode identity only) rather than
 * Lombok-generated, so @Builder/@Getter/@Setter are used instead of @Data.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserCategory {

  @Field("user_category_code")
  private String userCategoryCode;

  @Field("user_category_description")
  private String userCategoryDescription;

  @Field("user_sub_category_code")
  private String userSubCategoryCode;

  @Field("user_sub_category_description")
  private String userSubCategoryDescription;

  @Override
  public boolean equals(Object object) {
    if (object == null || getClass() != object.getClass()) {
      return false;
    }
    UserCategory that = (UserCategory) object;
    return Objects.equals(userCategoryCode, that.userCategoryCode)
        && Objects.equals(userSubCategoryCode, that.userSubCategoryCode);
  }

  @Override
  public int hashCode() {
    return Objects.hash(userCategoryCode, userSubCategoryCode);
  }
}
