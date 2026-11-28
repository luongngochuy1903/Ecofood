package com.ecofood.app.proposition.entity;

import com.ecofood.core.entity.BaseEntity;
import com.ecofood.core.enums.PlanStatusEnum;
import com.ecofood.core.enums.ProductTypeEnum;
import java.time.Instant;
import java.util.List;
import lombok.*;

/**
 * A Plan's own id doubles as the family id of a cloned Product-shaped
 * record: its name/description/category/image/ingredients/recipe/costing
 * live in their own product_process + attribute documents (see
 * CoreProductProcessClient), not on this class — this holds only what's
 * specific to being embedded in a Proposition (scoping and
 * review status; effective dates belong to the Proposition only). `type` is always PLAN; `version` is this Plan's OWN
 * version (bumped only by "Create New Version") and is unrelated to the
 * cloned process's own version, which always stays 1.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plan extends BaseEntity {
    /** Name of this entity's atomic Mongo counter — same pattern as ProductEntity.SEQUENCE_NAME. */
    public static final String SEQUENCE_NAME = "plan_sequence";

    @Builder.Default
    private ProductTypeEnum type = ProductTypeEnum.PLAN;
    private int version;
    /**
     * Which approved version of the Proposition's Product this Plan is
     * bound to — independently of its siblings and of the Proposition
     * itself, which no longer carries any product version at all. A future
     * change to this specific Product version (rejection, a newer version
     * taking over APPROVED) only affects Plans bound to it, not the whole
     * Proposition.
     */
    private Integer productVersion;
    private String code;
    private List<String> regionIds, tenantIds;
    private Instant approvedAt;
    private String approvedBy;
    private PlanStatusEnum status;
    private String rejectionReason;
    private String previousVersionId;
}
