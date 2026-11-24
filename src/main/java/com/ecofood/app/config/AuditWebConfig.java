package com.ecofood.app.config;

import lombok.RequiredArgsConstructor;
import com.ecofood.app.audit.entity.AuditLogEntity;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import static org.springframework.data.domain.Sort.Direction.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class AuditWebConfig implements WebMvcConfigurer {
  private final AuditInterceptor interceptor;

  @Bean ApplicationRunner auditIndexes(MongoTemplate mongo) {
    return args -> {
      var indexes = mongo.indexOps(AuditLogEntity.class);
      indexes.createIndex(new Index().on("tenantId", ASC).on("timestamp", DESC).on("_id", DESC));
      indexes.createIndex(new Index().on("tenantId", ASC).on("actorId", ASC).on("timestamp", DESC).on("_id", DESC));
    };
  }

  @Override public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(interceptor).addPathPatterns("/api/**");
  }
}
