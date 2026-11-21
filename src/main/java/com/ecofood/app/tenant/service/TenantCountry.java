package com.ecofood.app.tenant.service;
import com.ecofood.app.enterprise.entity.Communication;
import com.ecofood.app.enterprise.entity.CommunicationDetail;
import com.ecofood.app.tenant.entity.TenantEntity;
import java.util.*;
import java.util.stream.Stream;
/** Uses the country persisted by the onboarding and contact edit forms. */
public final class TenantCountry {
  private TenantCountry() {}
  public static String resolve(TenantEntity tenant) {
    var contact=tenant.getContact();
    if(contact==null||contact.getCommunications()==null)return null;
    return contact.getCommunications().stream().filter(Objects::nonNull)
        .sorted(Comparator.comparing(Communication::isPreferred).reversed())
        .flatMap(c -> c.getCommunicationDetails()==null?Stream.empty():c.getCommunicationDetails().stream()
            .filter(Objects::nonNull).sorted(Comparator.comparing(CommunicationDetail::isPreferred).reversed()))
        .map(CommunicationDetail::getIsoCountryCode).filter(Objects::nonNull)
        .map(c -> c.trim().toUpperCase(Locale.ROOT))
        .filter(c -> Arrays.asList(Locale.getISOCountries()).contains(c)).findFirst().orElse(null);
  }
}
