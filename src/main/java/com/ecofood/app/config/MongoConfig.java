package com.ecofood.app.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit MongoClient bean, bypassing Spring Boot's property-bound Mongo
 * auto-configuration — the sibling demotlcn prototype hit an unauthenticated
 * connection at runtime with both the uri-based and discrete-property forms
 * of that auto-configuration (never fully root-caused); this sidesteps it
 * entirely while keeping the connection string fully externalized via
 * spring.data.mongodb.uri.
 */
@Configuration
public class MongoConfig {

  @Value("${spring.data.mongodb.uri}")
  private String mongoUri;

  @Bean
  public MongoClient mongoClient() {
    ConnectionString connectionString = new ConnectionString(mongoUri);
    MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(connectionString).build();
    return MongoClients.create(settings);
  }
}
