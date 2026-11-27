package com.ecofood.app.proposition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.ecofood.core.client.CoreProductQueryClient;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.app.proposition.repository.PropositionRepository;
import java.util.List;
import java.util.Set;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

/**
 * Focused on PropositionQueryServiceImpl.list()'s Enterprise-directory branch
 * — PropositionAccess.current() itself (how enterpriseReadScope is widened to
 * owned REGIONAL_ENTERPRISE children) is covered by ProductAuthorizationTest's
 * equivalent scenarios, since both now share the same
 * CoreEnterpriseQueryClient.ownedEnterpriseIds widening.
 */
class PropositionQueryServiceImplTest {
  private final PropositionRepository repository = mock(PropositionRepository.class);
  private final PropositionAccess access = mock(PropositionAccess.class);
  private final PropositionMapper mapper = mock(PropositionMapper.class);
  private final PropositionParties parties = mock(PropositionParties.class);
  private final PropositionCurrencies currencies = mock(PropositionCurrencies.class);
  private final CoreProductQueryClient products = mock(CoreProductQueryClient.class);
  private final PropositionQueryServiceImpl service =
      new PropositionQueryServiceImpl(repository, access, mapper, parties, currencies, products);

  @Test
  void enterpriseDirectoryListingIsWidenedToOwnedRegionalEnterpriseChildren() {
    var ownEnterprise = new ObjectId();
    var childEnterprise = new ObjectId();
    var tenant =
        new PropositionTenantReference(
            new ObjectId().toHexString(), "Acme Co", ownEnterprise.toHexString(), null, null,
            List.of(), List.of(), false);
    var actor =
        new PropositionAccess.Actor(
            tenant,
            Set.of(),
            Set.of(),
            true,
            false,
            Set.of(ownEnterprise.toHexString(), childEnterprise.toHexString()));
    when(access.current()).thenReturn(actor);
    when(repository.findByEnterpriseIdInAndDeletedFalse(anyList())).thenReturn(List.of());

    service.list(null, PageRequest.of(0, 20));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ObjectId>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).findByEnterpriseIdInAndDeletedFalse(captor.capture());
    assertThat(captor.getValue()).containsExactlyInAnyOrder(ownEnterprise, childEnterprise);
    verify(repository, never()).findByEnterpriseIdAndDeletedFalse(any());
  }

  @Test
  void enterpriseDirectoryListingStaysScopedToItsOwnEnterpriseWhenItOwnsNoChildren() {
    var ownEnterprise = new ObjectId();
    var tenant =
        new PropositionTenantReference(
            new ObjectId().toHexString(), "Acme Co", ownEnterprise.toHexString(), null, null,
            List.of(), List.of(), false);
    var actor =
        new PropositionAccess.Actor(
            tenant, Set.of(), Set.of(), true, false, Set.of(ownEnterprise.toHexString()));
    when(access.current()).thenReturn(actor);
    when(repository.findByEnterpriseIdInAndDeletedFalse(anyList())).thenReturn(List.of());

    service.list(null, PageRequest.of(0, 20));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ObjectId>> captor = ArgumentCaptor.forClass(List.class);
    verify(repository).findByEnterpriseIdInAndDeletedFalse(captor.capture());
    assertThat(captor.getValue()).containsExactly(ownEnterprise);
  }
}
