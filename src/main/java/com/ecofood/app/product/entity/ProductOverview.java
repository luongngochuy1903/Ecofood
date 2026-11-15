package com.ecofood.app.product.entity;
import java.util.List;
import org.bson.types.ObjectId;
public record ProductOverview(String id, String processElementName, List<ObjectId> processElementAttribute) {}
