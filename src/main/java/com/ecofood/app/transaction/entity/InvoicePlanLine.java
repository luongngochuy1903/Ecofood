package com.ecofood.app.transaction.entity;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Costing snapshot is per Plan unit. Client prices are not proof of net revenue. */
public record InvoicePlanLine(String lineId, String orchestratorId, String planId, String planName,
    long quantity, @Field(targetType=FieldType.DECIMAL128) BigDecimal unitPrice,
    @Field(targetType=FieldType.DECIMAL128) BigDecimal amount, InvoiceSnapshot snapshot,
    boolean saleConfigurationVerified, Map<String, Object> additionalInfo) {}
