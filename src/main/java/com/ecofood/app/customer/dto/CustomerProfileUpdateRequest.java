package com.ecofood.app.customer.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.ecofood.app.config.BadRequestException;

/** Nullable fields mean "no change"; empty phone/address clear those fields. */
public record CustomerProfileUpdateRequest(
    @Size(max = 80) @Pattern(regexp = "^[^\\p{Cc}]*$", message = "Invalid first name") String firstName,
    @Size(max = 80) @Pattern(regexp = "^[^\\p{Cc}]*$", message = "Invalid last name") String lastName,
    @Pattern(regexp = "^$|^(?=.*[0-9])[+0-9() .-]{7,20}$", message = "Invalid phone number")
    String phoneNumber,
    @Size(max = 250) @Pattern(regexp = "^[^\\p{Cc}]*$", message = "Invalid address") String address) {
  @JsonAnySetter public void rejectUnknown(String name, Object ignored) { throw new BadRequestException("Unsupported profile field"); }
}
