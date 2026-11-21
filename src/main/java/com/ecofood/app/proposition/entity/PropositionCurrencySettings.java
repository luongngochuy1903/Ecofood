package com.ecofood.app.proposition.entity;

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
@Document(collection = "proposition_currency_settings")
public class PropositionCurrencySettings extends BaseEntity {
    private ObjectId enterpriseId;
    @Builder.Default
    private List<String> availableCurrencies = List.of("USD");
    @Builder.Default
    private String defaultCurrency = "USD";
    @Version
    private Long revision;
}
