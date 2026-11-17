package com.ecofood.app.proposition.service;

import com.ecofood.core.dto.*;
import java.math.*;
import java.util.Currency;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

@Component
public class MarketConfigurationCalculator {
    public MarketConfigurationResponse calculate(MarketConfigurationRequest r, String currency) {
        int digits = Currency.getInstance(currency).getDefaultFractionDigits();
        var price = amount(r.salePrice(), digits);
        var extra = amount(r.surcharge() == null ? BigDecimal.ZERO : r.surcharge(), digits);
        var rate = r.taxRate() == null ? BigDecimal.ZERO : r.taxRate();
        if (rate.signum() < 0
                || rate.compareTo(new BigDecimal("100")) > 0
                || rate.stripTrailingZeros().scale() > 4)
            bad("Tax must be between 0 and 100%, with at most four decimals");
        var tax = price.multiply(rate).divide(new BigDecimal("100"), digits, RoundingMode.HALF_UP);
        return new MarketConfigurationResponse(
                currency, price, rate, extra, tax, price.add(tax).add(extra));
    }

    private BigDecimal amount(BigDecimal value, int digits) {
        if (digits < 0
                || digits > 4
                || value == null
                || value.signum() < 0
                || value.stripTrailingZeros().scale() > digits
                || value.precision() - value.scale() > 15)
            bad("Invalid non-negative amount or currency decimal precision");
        return value.setScale(digits, RoundingMode.UNNECESSARY);
    }

    private void bad(String text) {
        throw PropositionAccess.error(HttpStatus.BAD_REQUEST, text);
    }
}
