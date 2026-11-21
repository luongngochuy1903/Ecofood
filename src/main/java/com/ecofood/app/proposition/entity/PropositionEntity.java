package com.ecofood.app.proposition.entity;

import com.ecofood.core.dto.*;
import com.ecofood.core.enums.PropositionStatusEnum;
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
    private ObjectId enterpriseId, owningTenantId, productId, productProcessId;
    private int productVersion;
    private String enterpriseName;
    private ProductProcessResponse sourceProcess;
    private PropositionDefinition definition;
    @Builder.Default
    private PropositionStatusEnum status = PropositionStatusEnum.DRAFT;
    private String approvedBy;
    private java.time.Instant approvedAt;

    /** Records created before separate Proposition approval remain Draft. */
    public PropositionStatusEnum getStatus() {
        return status == null ? PropositionStatusEnum.DRAFT : status;
    }

    @Builder.Default
    private List<Plan> plans = List.of();
    @Builder.Default
    private List<EconomicMetricsRequest> actualMetrics = List.of();
    @Builder.Default
    private List<PropositionChange> changes = List.of();
    @Version
    private Long revision;
}
