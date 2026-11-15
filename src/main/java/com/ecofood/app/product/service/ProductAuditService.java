package com.ecofood.app.product.service;
import com.ecofood.app.product.entity.ProductAuditEntity;
import com.ecofood.app.product.entity.ProductEntity;
import com.ecofood.app.product.entity.ProductProcessEntity;
import com.ecofood.app.product.repository.ProductAuditRepository;
import com.ecofood.core.security.AuthenticationContext;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
@Service @RequiredArgsConstructor
public class ProductAuditService {
  private final ProductAuditRepository repository;
  public void record(ProductEntity product,ProductProcessEntity version,String action,String details) {
    repository.save(ProductAuditEntity.builder().productId(product.getId()).version(version.getVersion())
      .action(action).details(details).actorId(AuthenticationContext.getUserId().toHexString())
      .eventTime(Instant.now()).build());
  }
}
