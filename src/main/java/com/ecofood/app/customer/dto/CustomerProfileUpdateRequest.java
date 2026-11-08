package com.ecofood.app.customer.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Nullable fields mean "no change"; empty phone/address clear those fields. */
public record CustomerProfileUpdateRequest(
    @Size(max = 80) String firstName,
    @Size(max = 80) String lastName,
    @Pattern(regexp = "^$|^[+0-9() .-]{7,20}$", message = "Invalid phone number")
    String phoneNumber,
    @Size(max = 250) String address) {}
