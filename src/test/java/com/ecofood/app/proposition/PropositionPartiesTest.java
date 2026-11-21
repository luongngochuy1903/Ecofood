package com.ecofood.app.proposition;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.ecofood.app.proposition.service.PropositionParties;
import com.ecofood.core.client.CoreTenantQueryClient;
import com.ecofood.core.dto.PropositionTenantReference;
import com.ecofood.core.enums.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PropositionPartiesTest {
    @Test
    void directoryScopeRequiresSubTenantAndAcceptsBothPartnerTypes() {
        var tenants = mock(CoreTenantQueryClient.class);
        var parties = new PropositionParties(tenants);
        var owner =
                ref("owner", PartnerTypeEnum.ENTERPRISE, TenantTypeEnum.MASTER_TENANT, "VN", false);
        var brand = ref("brand", PartnerTypeEnum.BRAND, TenantTypeEnum.SUB_TENANT, "VN", false);
        var partner =
                ref(
                        "partner",
                        PartnerTypeEnum.ECOSYSTEM_PARTNER,
                        TenantTypeEnum.SUB_TENANT,
                        "TH",
                        false);
        var master =
                ref(
                        "master",
                        PartnerTypeEnum.ECOSYSTEM_PARTNER,
                        TenantTypeEnum.MASTER_TENANT,
                        "VN",
                        false);
        var foreign = ref("foreign", PartnerTypeEnum.BRAND, TenantTypeEnum.SUB_TENANT, "VN", false);
        var deleted = ref("deleted", PartnerTypeEnum.BRAND, TenantTypeEnum.SUB_TENANT, "VN", true);
        var missing = ref("missing", PartnerTypeEnum.BRAND, TenantTypeEnum.SUB_TENANT, null, false);
        when(tenants.getScopedTenantIds("owner"))
                .thenReturn(List.of("owner", "brand", "partner", "master", "deleted", "missing"));
        when(tenants.propositionReferences())
                .thenReturn(List.of(owner, brand, partner, master, foreign, deleted, missing));
        assertThat(parties.directoryPartners(owner))
                .extracting(PropositionTenantReference::id)
                .containsExactly("brand", "missing", "partner");
        assertThat(parties.countries(owner, List.of("brand", "partner")))
                .containsExactly("TH", "VN");
        assertThatThrownBy(() -> parties.selected(owner, List.of("foreign")))
                .hasMessageContaining("Tenant Directory");
        assertThatThrownBy(() -> parties.selected(owner, List.of("master")))
                .hasMessageContaining("Sub-Tenants");
        assertThatThrownBy(() -> parties.countries(owner, List.of("missing")))
                .hasMessageContaining("contact country");
    }

    private PropositionTenantReference ref(
            String id,
            PartnerTypeEnum type,
            TenantTypeEnum tenant,
            String country,
            boolean deleted) {
        return new PropositionTenantReference(
                id, id, null, type, tenant, List.of(id), List.of(), deleted, country);
    }
}
