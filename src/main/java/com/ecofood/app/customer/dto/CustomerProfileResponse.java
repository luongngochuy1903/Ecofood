package com.ecofood.app.customer.dto;

import java.time.Instant;

public record CustomerProfileResponse(String userId, String tenantId, String email,
                                      String firstName, String lastName,
                                      String phoneNumber, String address, Instant updatedAt) {}
