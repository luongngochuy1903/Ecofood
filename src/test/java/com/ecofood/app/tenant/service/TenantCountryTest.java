package com.ecofood.app.tenant.service;
import static org.assertj.core.api.Assertions.*;
import com.ecofood.app.enterprise.entity.*;
import com.ecofood.app.tenant.entity.TenantEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
class TenantCountryTest {
  @Test void countryComesFromPreferredOnboardingAddressAndIsNormalized() {
    var tenant=TenantEntity.builder().contact(Contact.builder().communications(List.of(
        Communication.builder().communicationDetails(List.of(CommunicationDetail.builder().isoCountryCode("TH").build())).build(),
        Communication.builder().preferred(true).communicationDetails(List.of(CommunicationDetail.builder().preferred(true).isoCountryCode(" vn ").build())).build())).build()).build();
    assertThat(TenantCountry.resolve(tenant)).isEqualTo("VN");
    assertThat(TenantCountry.resolve(TenantEntity.builder().build())).isNull();
  }
}
