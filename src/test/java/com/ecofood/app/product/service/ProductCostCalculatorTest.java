package com.ecofood.app.product.service;
import static org.assertj.core.api.Assertions.*;
import com.ecofood.core.dto.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
class ProductCostCalculatorTest {
  private final ProductCostCalculator calculator=new ProductCostCalculator();
  @Test void preservesCurrencyWithOrWithoutSellingPrice() {
    var costs = new ProductCostingRequest(BigDecimal.ONE,null,null,null,null,null,null,"USD");
    assertThat(calculator.calculate(costs,null).getCurrency()).isEqualTo("USD");
    assertThat(calculator.calculate(costs,new ProductPricingRequest(BigDecimal.TEN,null," usd ")).getCurrency()).isEqualTo("USD");
    assertThat(calculator.calculate(null,null).getCurrency()).isEqualTo("VND");
    assertThatThrownBy(() -> calculator.calculate(costs,new ProductPricingRequest(BigDecimal.TEN,null,"EUR")))
        .hasMessageContaining("same currency");
    assertThatThrownBy(() -> calculator.calculate(new ProductCostingRequest(null,null,null,null,null,null,null,"INVALID"),null))
        .hasMessageContaining("currency");
  }
  @Test void calculatesCostAndSuggestedPriceWithMarginAndTax() {
    var result=calculator.calculate(new ProductCostingRequest(new BigDecimal("50000"),new BigDecimal("5000"),
        new BigDecimal("10000"),new BigDecimal("3000"),new BigDecimal("2000"),new BigDecimal("30"),new BigDecimal("10")),
        new ProductPricingRequest(new BigDecimal("120000"),null,"VND"));
    assertThat(result.getTotalCost()).isEqualByComparingTo("20000");
    assertThat(result.getBasePrice()).isEqualByComparingTo("31428.57");
    assertThat(result.getIngredientCost()).isEqualByComparingTo("0");
    assertThat(result.getSellingPrice()).isEqualByComparingTo("120000");
  }
  @Test void doesNotTreatOldCostPriceAsConfigurableIngredientCost() {
    var result=calculator.calculate(null,new ProductPricingRequest(BigDecimal.TEN,BigDecimal.ONE,"VND"));
    assertThat(result.getIngredientCost()).isEqualByComparingTo("0");
    assertThat(result.getTotalCost()).isEqualByComparingTo("0");
  }
  @Test void zeroCostIsValidButNegativeCostsAndMargin100AreRejected() {
    assertThat(calculator.calculate(null,null).getBasePrice()).isEqualByComparingTo("0");
    assertThatThrownBy(()->calculator.calculate(new ProductCostingRequest(BigDecimal.ONE,null,null,null,null,new BigDecimal("100"),null),null)).hasMessageContaining("below 100");
    assertThatThrownBy(()->calculator.calculate(new ProductCostingRequest(new BigDecimal("-1"),null,null,null,null,null,null),null)).hasMessageContaining("negative");
  }
}
