package com.ecofood.app.proposition;

import static org.assertj.core.api.Assertions.*;
import com.ecofood.app.proposition.service.MarketConfigurationCalculator;
import com.ecofood.core.dto.MarketConfigurationRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketConfigurationCalculatorTest {
    private final MarketConfigurationCalculator calculator = new MarketConfigurationCalculator();

    @Test
    void moneyUsesExactCurrencyMinorUnitsAndHalfUpTax() {
        var r =
                calculator.calculate(
                        new MarketConfigurationRequest(
                                "USD",
                                new BigDecimal("0.10"),
                                new BigDecimal("5"),
                                new BigDecimal("0.20")),
                        "USD");
        assertThat(r.taxAmount()).isEqualByComparingTo("0.01");
        assertThat(r.totalPrice()).isEqualByComparingTo("0.31");
        assertThat(
                        calculator
                                .calculate(
                                        new MarketConfigurationRequest(
                                                "KWD", new BigDecimal("1.123"), null, null),
                                        "KWD")
                                .totalPrice())
                .isEqualByComparingTo("1.123");
        assertThatThrownBy(
                        () ->
                                calculator.calculate(
                                        new MarketConfigurationRequest(
                                                "USD", new BigDecimal("1.001"), null, null),
                                        "USD"))
                .hasMessageContaining("precision");
        assertThatThrownBy(
                        () ->
                                calculator.calculate(
                                        new MarketConfigurationRequest(
                                                "JPY", new BigDecimal("1.1"), null, null),
                                        "JPY"))
                .hasMessageContaining("precision");
    }
}
