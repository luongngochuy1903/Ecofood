package com.ecofood.app.product.service;
import static org.assertj.core.api.Assertions.*;
import com.ecofood.app.product.entity.ProductProcessEntity;
import com.ecofood.core.dto.*;
import com.ecofood.core.enums.ProductStatusEnum;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
class ProductValidationTest {
  private final ProductValidation validator=new ProductValidation();
  @Test void validFixedPriceAndRecipe() {
    validator.validateDraft("Product A",List.of(new ProductIngredientRequest("ing-1",BigDecimal.ONE,"GRAM")),
      new ProductRecipeRequest(true,List.of(new ProductRecipeStepRequest(1,"Mix","",1))),
      new ProductPricingRequest(new BigDecimal("120000"),new BigDecimal("90000"),"VND"));
  }
  @Test void rejectsNegativePrice() {
    assertThatThrownBy(()->validator.validateDraft("Product",List.of(),null,
      new ProductPricingRequest(new BigDecimal("-1"),BigDecimal.ZERO,"VND")))
      .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Negative/invalid price");
  }
  @Test void rejectsSubmitWithoutPricing() {
    ProductProcessEntity version=ProductProcessEntity.builder().name("Product")
      .status(ProductStatusEnum.DRAFT).ingredients(List.of()).build();
    assertThatThrownBy(()->validator.validateSubmit(version))
      .isInstanceOf(ResponseStatusException.class).hasMessageContaining("Pricing is required");
  }
  @Test void rejectsRecipeWithNoSteps() {
    assertThatThrownBy(()->validator.validateDraft("Product",List.of(),new ProductRecipeRequest(true,List.of()),null))
      .isInstanceOf(ResponseStatusException.class).hasMessageContaining("no steps");
  }
}
