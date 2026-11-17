package com.ecofood.app.proposition;

import static org.assertj.core.api.Assertions.*;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.service.PropositionValidation;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.*;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.*;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

class PropositionValidationTest {
    @Test
    void periodsAreHalfOpenAndImageOverridesMustBeHttpUrls() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validation = new PropositionValidation(factory.getValidator());
            String tenant = new ObjectId().toHexString();
            var d =
                    new PropositionDefinition(
                            "Distribution",
                            null,
                            List.of("TH"),
                            new ObjectId().toHexString(),
                            List.of(tenant),
                            "USD",
                            null,
                            null,
                            null,
                            List.of(),
                            List.of(),
                            null,
                            null,
                            null,
                            Set.of(PlanOverrideField.IMAGE_URL));
            var p =
                    PropositionEntity.builder()
                            .definition(d)
                            .sourceProcess(
                                    new ProductProcessResponse(
                                            "process",
                                            "product",
                                            1,
                                            0L,
                                            ProductStatusEnum.APPROVED,
                                            "v1",
                                            null,
                                            null,
                                            null,
                                            false,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null))
                            .build();
            var one =
                    Plan.builder()
                            .name("One")
                            .regionIds(List.of("TH"))
                            .tenantIds(List.of(tenant))
                            .startDate(Instant.parse("2027-01-01T00:00:00Z"))
                            .endDate(Instant.parse("2027-02-01T00:00:00Z"))
                            .build();
            one.setId(new ObjectId());
            p.setPlans(List.of(one));
            var next =
                    Plan.builder()
                            .name("Next")
                            .regionIds(List.of("TH"))
                            .tenantIds(List.of(tenant))
                            .startDate(one.getEndDate())
                            .endDate(Instant.parse("2027-03-01T00:00:00Z"))
                            .build();
            next.setId(new ObjectId());
            var eligible =
                    List.of(
                            new PropositionTenantReference(
                                    tenant,
                                    "Distributor",
                                    null,
                                    PartnerTypeEnum.ECOSYSTEM_PARTNER,
                                    TenantTypeEnum.SUB_TENANT,
                                    List.of(tenant),
                                    List.of(),
                                    false,
                                    "TH"));
            assertThatCode(() -> validation.plan(p, next, eligible, false))
                    .doesNotThrowAnyException();
            next.setStartDate(Instant.parse("2027-01-31T00:00:00Z"));
            assertThatThrownBy(() -> validation.plan(p, next, eligible, false))
                    .hasMessageContaining("overlaps");
            next.setStartDate(one.getEndDate());
            next.setProductOverrides(new PlanProductOverrides(null, null, "relative.png"));
            assertThatThrownBy(() -> validation.plan(p, next, eligible, false))
                    .hasMessageContaining("HTTP(S)");
            next.setProductOverrides(
                    new PlanProductOverrides(null, null, "https://example.com/image.png"));
            assertThatCode(() -> validation.plan(p, next, eligible, false))
                    .doesNotThrowAnyException();
        }
    }
}
