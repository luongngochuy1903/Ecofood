package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.ProductProcessEntity;
import com.ecofood.core.dto.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
@Component
public class ProductValidation {
  public void validateDraft(String name, List<ProductIngredientRequest> ingredients,
      ProductRecipeRequest recipe, ProductPricingRequest pricing) {
    if (name == null || name.isBlank()) bad("Product name is required");
    if (ingredients != null) {
      Set<String> ids = new HashSet<>();
      for (ProductIngredientRequest ingredient: ingredients) {
        if (ingredient == null || ingredient.name() == null || ingredient.name().isBlank()
          || ingredient.unit() == null || ingredient.unit().isBlank() || ingredient.quantity() == null
          || ingredient.quantity().compareTo(BigDecimal.ZERO) <= 0) bad("Invalid ingredient");
        String key = ingredient.ingredientId() != null && !ingredient.ingredientId().isBlank()
            ? ingredient.ingredientId().trim() : ingredient.name().trim().toLowerCase(Locale.ROOT);
        if (!ids.add(key)) bad("Duplicate ingredient: " + ingredient.name());
      }
    }
    if (recipe != null && recipe.enabled()) {
      if (recipe.preparationMinutes() != null && recipe.preparationMinutes() < 0
          || recipe.cookingMinutes() != null && recipe.cookingMinutes() < 0) bad("Recipe times cannot be negative");
      if (recipe.steps() == null || recipe.steps().isEmpty()) bad("Recipe enabled but has no steps");
      Set<Integer> order = new HashSet<>();
      for (ProductRecipeStepRequest step : recipe.steps()) {
        if (step == null || step.order() == null || step.order() < 1 || step.name() == null
          || step.name().isBlank() || step.durationMinutes() == null || step.durationMinutes() < 0)
          bad("Invalid recipe step");
        if (!order.add(step.order())) bad("Duplicate recipe step order");
      }
    }
    if (pricing != null) {
      if (pricing.sellingPrice() == null || pricing.sellingPrice().signum() < 0
        || pricing.costPrice() != null && pricing.costPrice().signum() < 0) bad("Negative/invalid price");
      if (pricing.currency() != null && !pricing.currency().equals("VND")) bad("Only VND supported in phase 1");
    }
  }
  public void validateDefinition(String code, String category, String imageUrl) {
    if (code == null || !code.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
      bad("Product code is required (1-64 letters, digits, dots, underscores or hyphens)");
    if (category == null || category.isBlank()) bad("Category is required");
    if (imageUrl != null && !imageUrl.isBlank()) {
      try {
        java.net.URI uri = java.net.URI.create(imageUrl);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
            || uri.getHost() == null) bad("Product image must be an HTTP(S) URL");
      } catch (IllegalArgumentException ex) { bad("Product image must be an HTTP(S) URL"); }
    }
  }
  public void validateSubmit(ProductProcessEntity version) {
    validateDraft(version.getName(),version.getIngredients(),version.getRecipe(),version.getPricing());
    if (version.getPricing() == null) bad("Pricing is required before submit");
    validateDefinition(version.getCode(), version.getCategory(), version.getImageUrl());
    if (version.getPricing().sellingPrice().signum() < 0) bad("Selling price cannot be negative");
  }
  private static void bad(String message) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
