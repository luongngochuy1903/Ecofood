package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.ProductCostingAttribute;
import com.ecofood.core.dto.ProductCostingRequest;
import com.ecofood.core.dto.ProductPricingRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
@Component
public class ProductCostCalculator {
  public ProductCostingAttribute calculate(ProductCostingRequest request,ProductPricingRequest pricing) {
    String currency = ProductCurrency.normalize(pricing != null ? pricing.currency() : request == null ? null : request.currency());
    if (request != null && request.currency() != null
        && !ProductCurrency.normalize(request.currency()).equals(currency))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Costing and pricing must use the same currency");
    BigDecimal ingredient=amount(request==null ? pricing==null?null:pricing.costPrice() : request.ingredientCost());
    BigDecimal packaging=amount(request==null?null:request.packagingCost());
    BigDecimal labor=amount(request==null?null:request.laborCost());
    BigDecimal operation=amount(request==null?null:request.operationCost());
    BigDecimal marketing=amount(request==null?null:request.marketingFee());
    BigDecimal margin=amount(request==null?null:request.targetMargin());
    BigDecimal tax=amount(request==null?null:request.tax());
    if(margin.compareTo(new BigDecimal("100"))>=0 || tax.compareTo(new BigDecimal("100"))>0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Margin must be below 100%; tax cannot exceed 100%");
    BigDecimal total=ingredient.add(packaging).add(labor).add(operation).add(marketing);
    BigDecimal base=total.divide(BigDecimal.ONE.subtract(margin.movePointLeft(2)),12,RoundingMode.HALF_UP)
        .multiply(BigDecimal.ONE.add(tax.movePointLeft(2))).setScale(2,RoundingMode.HALF_UP);
    return ProductCostingAttribute.builder().ingredientCost(ingredient).packagingCost(packaging).laborCost(labor)
        .operationCost(operation).marketingFee(marketing).targetMargin(margin).tax(tax).totalCost(total)
        .basePrice(base).sellingPrice(pricing==null?null:amount(pricing.sellingPrice())).currency(currency)
        .note(pricing==null?null:pricing.note()).build();
  }
  private BigDecimal amount(BigDecimal value) {
    if(value==null)return BigDecimal.ZERO;
    if(value.signum()<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cost and percentage values cannot be negative");
    return value;
  }
}
