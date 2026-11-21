package com.ecofood.app.proposition.entity;

import com.ecofood.core.dto.*;
import com.ecofood.core.entity.BaseEntity;
import com.ecofood.core.enums.PlanStatusEnum;
import java.time.Instant;
import java.util.List;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plan extends BaseEntity {
    private int version;
    private String name, code, description;
    private List<String> regionIds, tenantIds;
    private PlanProductOverrides productOverrides;
    private MarketConfigurationResponse marketConfiguration;
    private Instant startDate, endDate, approvedAt;
    private String approvedBy;
    private PlanStatusEnum status;
    private String previousVersionId;
}
