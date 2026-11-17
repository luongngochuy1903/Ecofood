package com.ecofood.app.proposition.service;

import com.ecofood.core.enums.PlanStatusEnum;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

@Configuration
public class PropositionMongoConfiguration {
    @Bean
    public MongoCustomConversions propositionMongoConversions() {
        return MongoCustomConversions.create(
                adapter -> adapter.registerConverter(new LegacyPlanStatusReader()));
    }

    /** Read old records without restoring the removed activation lifecycle or writing business data. */
    @ReadingConverter
    public static class LegacyPlanStatusReader implements Converter<String, PlanStatusEnum> {
        @Override
        public PlanStatusEnum convert(String status) {
            return switch (status) {
                case "LIVE" -> PlanStatusEnum.APPROVED;
                case "DEACTIVATED", "EXPIRED" -> PlanStatusEnum.DRAFT;
                default -> PlanStatusEnum.valueOf(status);
            };
        }
    }
}
