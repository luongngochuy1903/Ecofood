package com.ecofood.app.feedback.dto;

import com.ecofood.app.config.BadRequestException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record SubmitServiceFeedbackRequest(
    @NotNull @DecimalMin("1") @DecimalMax("5") @Digits(integer = 1, fraction = 0) BigDecimal rating,
    @Size(max = 2000) String comment) {
  @JsonAnySetter
  public void rejectUnknownField(String name, Object value) {
    throw new BadRequestException("Unsupported feedback field");
  }
}
