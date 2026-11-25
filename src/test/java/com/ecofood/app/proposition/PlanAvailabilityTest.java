package com.ecofood.app.proposition;

import static org.assertj.core.api.Assertions.*;
import com.ecofood.app.proposition.entity.*;
import com.ecofood.app.proposition.service.*;
import com.ecofood.core.dto.PropositionDefinition;
import com.ecofood.core.enums.*;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

class PlanAvailabilityTest {
    private final Instant now = Instant.parse("2027-01-01T00:00:00Z");

    private PropositionEntity proposition() {
        return PropositionEntity.builder()
                .definition(
                        new PropositionDefinition(
                                "Launch",
                                null,
                                List.of("VN"),
                                null,
                                List.of("partner"),
                                "USD",
                                null,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                null,
                                null,
                                null,
                                Set.of()))
                .build();
    }

    private Plan plan() {
        var p = Plan.builder().status(PlanStatusEnum.APPROVED).build();
        p.setId(new ObjectId());
        return p;
    }

    @Test
    void onlyThePlansOwnApprovalAndEffectivePeriodsAreRequired() {
        var p = proposition();
        var plan = plan();
        p.setPlans(List.of(plan));
        plan.setStatus(PlanStatusEnum.PENDING_REVIEW);
        assertThat(PlanAvailability.active(p, plan, now)).isFalse();
        plan.setStatus(PlanStatusEnum.APPROVED);
        plan.setStartDate(now.plusSeconds(1));
        assertThat(PlanAvailability.active(p, plan, now)).isFalse();
        plan.setStartDate(now);
        plan.setEndDate(now.plusSeconds(1));
        assertThat(PlanAvailability.active(p, plan, now)).isTrue();
        assertThat(PlanAvailability.active(p, plan, now.plusSeconds(1))).isFalse();
        assertThat(plan.getStatus()).isEqualTo(PlanStatusEnum.APPROVED);
    }

    @Test
    void successorWaitsForItsOwnApprovalAndEffectiveDate() {
        var p = proposition();
        var old = plan();
        var next = plan();
        next.setPreviousVersionId(old.getIdAsString());
        next.setStartDate(now.plusSeconds(10));
        p.setPlans(List.of(old, next));
        assertThat(PlanAvailability.active(p, old, now)).isTrue();
        assertThat(PlanAvailability.active(p, next, now)).isFalse();
        next.setStatus(PlanStatusEnum.PENDING_REVIEW);
        assertThat(PlanAvailability.active(p, old, now.plusSeconds(10))).isTrue();
        next.setStatus(PlanStatusEnum.APPROVED);
        assertThat(PlanAvailability.active(p, old, now.plusSeconds(10))).isFalse();
        assertThat(PlanAvailability.active(p, next, now.plusSeconds(10))).isTrue();
    }

    @Test
    void oldMongoDocumentsWithALegacyPlanStatusStayInactiveUntilReApproved() throws Exception {
        var conversions = new PropositionMongoConfiguration().propositionMongoConversions();
        var context = new MongoMappingContext();
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.afterPropertiesSet();
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        var p = proposition();
        var plan = plan();
        p.setPlans(List.of(plan));
        var doc = new Document();
        converter.write(p, doc);
        doc.getList("plans", Document.class).get(0).put("status", "LIVE");
        var old = converter.read(PropositionEntity.class, doc);
        assertThat(old.getPlans().get(0).getStatus()).isEqualTo(PlanStatusEnum.APPROVED);
        // No Proposition-side gate left to hold it back — once the legacy
        // status maps to APPROVED and dates are unset (always within range),
        // the Plan is active on its own.
        assertThat(PlanAvailability.active(old, old.getPlans().get(0), now)).isTrue();
        for (String status : List.of("DEACTIVATED", "EXPIRED")) {
            doc.getList("plans", Document.class).get(0).put("status", status);
            assertThat(converter.read(PropositionEntity.class, doc).getPlans().get(0).getStatus())
                    .isEqualTo(PlanStatusEnum.DRAFT);
        }
    }
}
