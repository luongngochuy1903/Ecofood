package com.ecofood.app.config;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

/**
 * Explicit MongoClient bean, bypassing Spring Boot's property-bound Mongo
 * auto-configuration — the sibling demotlcn prototype hit an unauthenticated
 * connection at runtime with both the uri-based and discrete-property forms
 * of that auto-configuration (never fully root-caused); this sidesteps it
 * entirely while keeping the connection string fully externalized via
 * spring.data.mongodb.uri.
 *
 * The MongoDatabaseFactory bean below exists for the same reason: Boot's
 * auto-configured one silently ignored spring.data.mongodb.database here
 * (confirmed — every write landed in the driver's own built-in "test"
 * fallback database regardless of that property's value) once a custom
 * MongoClient bean was already present. Building it explicitly from the
 * same property removes the ambiguity entirely.
 */
@Configuration
public class MongoConfig {

  @Value("${spring.data.mongodb.uri}")
  private String mongoUri;

  @Value("${spring.data.mongodb.database}")
  private String databaseName;

  @Bean
  public MongoClient mongoClient() {
    ConnectionString connectionString = new ConnectionString(mongoUri);
    MongoClientSettings settings = MongoClientSettings.builder().applyConnectionString(connectionString).build();
    return MongoClients.create(settings);
  }

  @Bean
  public MongoDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient) {
    return new SimpleMongoClientDatabaseFactory(mongoClient, databaseName);
  }
}
