package com.ecofood.app.tenant.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ecofood.app.tenant.entity.TenantEntity;
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
class TenantRepositoryImplTest {

  @Mock private MongoTemplate mongoTemplate;

  private TenantRepositoryImpl tenantRepositoryImpl;

  private final ObjectId callerTenantId = new ObjectId();

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    // Mockito's defaults for unstubbed calls (0L, empty List) are exactly
    // what most tests here need, so count/find are left unstubbed unless a
    // specific test needs a different value.
    tenantRepositoryImpl = new TenantRepositoryImpl(mongoTemplate);
  }

  @Test
  void unrestrictedQueryHasNoScopingCriteria() {
    tenantRepositoryImpl.findDirectory(null, true, null, PageRequest.of(0, 20));

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verifyCountAndFindUsedTheSameQuery(captor);
    Document queryObject = captor.getValue().getQueryObject();
    assertThat(queryObject).isEmpty();
  }

  @Test
  void scopedQueryMatchesSelfOrAMasterTenantRelationPointingAtTheCaller() {
    tenantRepositoryImpl.findDirectory(callerTenantId, false, null, PageRequest.of(0, 20));

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verifyCountAndFindUsedTheSameQuery(captor);
    Document queryObject = captor.getValue().getQueryObject();

    assertThat(queryObject).containsKey("$or");
    @SuppressWarnings("unchecked")
    List<Document> orClauses = (List<Document>) queryObject.get("$or");
    assertThat(orClauses).hasSize(2);
    assertThat(orClauses.get(0)).containsEntry("_id", callerTenantId);

    Document elemMatch = (Document) orClauses.get(1).get("party_to_party_relation");
    Document criteria = (Document) elemMatch.get("$elemMatch");
    assertThat(criteria).containsEntry("relationship_type_code", "MT");
    assertThat(criteria).containsEntry("to_party_identifier", callerTenantId);
  }

  @Test
  void searchAddsACaseInsensitiveRegexOnTenantName() {
    tenantRepositoryImpl.findDirectory(null, true, "Acme", PageRequest.of(0, 20));

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verifyCountAndFindUsedTheSameQuery(captor);
    Document queryObject = captor.getValue().getQueryObject();

    java.util.regex.Pattern regex = (java.util.regex.Pattern) queryObject.get("tenant_name");
    assertThat(regex.pattern()).isEqualTo("\\QAcme\\E");
    assertThat(regex.flags() & java.util.regex.Pattern.CASE_INSENSITIVE).isNotZero();
  }

  @Test
  void blankSearchAddsNoFilter() {
    tenantRepositoryImpl.findDirectory(null, true, "   ", PageRequest.of(0, 20));

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verifyCountAndFindUsedTheSameQuery(captor);
    assertThat(captor.getValue().getQueryObject()).doesNotContainKey("tenant_name");
  }

  @Test
  void appliesThePageableForSkipLimitAndSort() {
    when(mongoTemplate.count(any(Query.class), eq(TenantEntity.class))).thenReturn(45L);

    var result =
        tenantRepositoryImpl.findDirectory(
            null, true, null, PageRequest.of(2, 10, Sort.by(Sort.Direction.ASC, "tenantName")));

    assertThat(result.getTotalElements()).isEqualTo(45);
    assertThat(result.getNumber()).isEqualTo(2);
    assertThat(result.getSize()).isEqualTo(10);

    ArgumentCaptor<Query> findCaptor = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(findCaptor.capture(), eq(TenantEntity.class));
    Query findQuery = findCaptor.getValue();
    assertThat(findQuery.getSkip()).isEqualTo(20);
    assertThat(findQuery.getLimit()).isEqualTo(10);
  }

  private void verifyCountAndFindUsedTheSameQuery(ArgumentCaptor<Query> captor) {
    verify(mongoTemplate).count(captor.capture(), eq(TenantEntity.class));
    verify(mongoTemplate).find(any(Query.class), eq(TenantEntity.class));
  }

  @Test
  void findScopedTenantIdsUsesTheSameScopeCriteriaAsFindDirectory() {
    TenantEntity self = TenantEntity.builder().tenantName("Self").build();
    self.setId(callerTenantId);
    ObjectId subTenantId = new ObjectId();
    TenantEntity subTenant = TenantEntity.builder().tenantName("Sub").build();
    subTenant.setId(subTenantId);
    when(mongoTemplate.find(any(Query.class), eq(TenantEntity.class))).thenReturn(List.of(self, subTenant));

    List<ObjectId> ids = tenantRepositoryImpl.findScopedTenantIds(callerTenantId);

    assertThat(ids).containsExactly(callerTenantId, subTenantId);

    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(captor.capture(), eq(TenantEntity.class));
    Document queryObject = captor.getValue().getQueryObject();
    assertThat(queryObject).containsKey("$or");
    assertThat(captor.getValue().getFieldsObject()).containsEntry("_id", 1);
  }
}
