package com.ecofood.app.userACL.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.app.userACL.entity.Group;
import com.ecofood.core.enums.GroupType;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class GroupRepositoryImplTest {

  @Mock private MongoTemplate mongoTemplate;

  private GroupRepositoryImpl groupRepositoryImpl;

  private final ObjectId callerTenantId = new ObjectId();

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    groupRepositoryImpl = new GroupRepositoryImpl(mongoTemplate);
  }

  @Test
  void alwaysFiltersByGroupTypeEvenWhenUnrestricted() {
    groupRepositoryImpl.findDirectory(GroupType.ACCESS_CONTROL_GROUP, null, true, null, PageRequest.of(0, 20));

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verifyCountAndFindUsedTheSameQuery(captor);
    Document queryObject = captor.getValue().getQueryObject();
    assertThat(queryObject).containsEntry("group_type", GroupType.ACCESS_CONTROL_GROUP);
    assertThat(queryObject).doesNotContainKey("party_to_party_relation");
  }

  @Test
  void scopedQueryMatchesATenantRelationPointingAtOneOfTheScopedTenantIds() {
    List<ObjectId> scopedTenantIds = List.of(callerTenantId);
    groupRepositoryImpl.findDirectory(
        GroupType.ACCESS_CONTROL_GROUP, scopedTenantIds, false, null, PageRequest.of(0, 20));

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verifyCountAndFindUsedTheSameQuery(captor);
    Document queryObject = captor.getValue().getQueryObject();

    assertThat(queryObject).containsKey("party_to_party_relation");
    Document elemMatch = (Document) queryObject.get("party_to_party_relation");
    Document criteria = (Document) elemMatch.get("$elemMatch");
    assertThat(criteria).containsEntry("relationship_type_code", "TENANT");
  }

  @Test
  void searchAddsACaseInsensitiveRegexOnGroupName() {
    groupRepositoryImpl.findDirectory(
        GroupType.ACCESS_CONTROL_GROUP, null, true, "Billing", PageRequest.of(0, 20));

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verifyCountAndFindUsedTheSameQuery(captor);
    Document queryObject = captor.getValue().getQueryObject();

    java.util.regex.Pattern regex = (java.util.regex.Pattern) queryObject.get("group_name");
    assertThat(regex.pattern()).isEqualTo("\\QBilling\\E");
    assertThat(regex.flags() & java.util.regex.Pattern.CASE_INSENSITIVE).isNotZero();
  }

  @Test
  void blankSearchAddsNoFilter() {
    groupRepositoryImpl.findDirectory(GroupType.ACCESS_CONTROL_GROUP, null, true, "   ", PageRequest.of(0, 20));

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verifyCountAndFindUsedTheSameQuery(captor);
    assertThat(captor.getValue().getQueryObject()).doesNotContainKey("group_name");
  }

  @Test
  void appliesThePageableForSkipLimitAndSort() {
    when(mongoTemplate.count(any(Query.class), eq(Group.class))).thenReturn(45L);

    var result =
        groupRepositoryImpl.findDirectory(
            GroupType.ACCESS_CONTROL_GROUP, null, true, null,
            PageRequest.of(2, 10, Sort.by(Sort.Direction.ASC, "name")));

    assertThat(result.getTotalElements()).isEqualTo(45);
    assertThat(result.getNumber()).isEqualTo(2);
    assertThat(result.getSize()).isEqualTo(10);

    ArgumentCaptor<Query> findCaptor = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(findCaptor.capture(), eq(Group.class));
    Query findQuery = findCaptor.getValue();
    assertThat(findQuery.getSkip()).isEqualTo(20);
    assertThat(findQuery.getLimit()).isEqualTo(10);
  }

  private void verifyCountAndFindUsedTheSameQuery(ArgumentCaptor<Query> captor) {
    verify(mongoTemplate).count(captor.capture(), eq(Group.class));
    verify(mongoTemplate).find(any(Query.class), eq(Group.class));
  }
}
