package com.ecofood.app.proposition.entity;

import com.ecofood.core.dto.*;
import com.ecofood.core.entity.BaseEntity;
import java.util.List;
import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "proposition")
public class PropositionEntity extends BaseEntity {
    private ObjectId enterpriseId, owningTenantId, productId;
    private String enterpriseName;
    private PropositionDefinition definition;

    @Builder.Default
    private List<Plan> plans = List.of();
    @Builder.Default
    private List<EconomicMetricsRequest> actualMetrics = List.of();
    /** Marks actuals rebuilt from immutable invoices with tenant/month/currency dimensions. */
    private Integer actualMetricsVersion;
    @Builder.Default
    private List<PropositionChange> changes = List.of();
    @Version
    private Long revision;
}
