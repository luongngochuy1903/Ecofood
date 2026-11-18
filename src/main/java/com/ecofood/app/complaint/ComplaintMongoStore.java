package com.ecofood.app.complaint;

import com.mongodb.*;
import com.mongodb.client.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.*;
import org.springframework.stereotype.Component;

/** Complaint-only durability settings; never mutates the shared application's MongoTemplate/client. */
@Component
public class ComplaintMongoStore {
  private final MongoTemplate template;
  @Autowired
  public ComplaintMongoStore(MongoClient client, MongoTemplate shared, @Value("${spring.data.mongodb.database}") String database) {
    var factory = new SimpleMongoClientDatabaseFactory(client, database) {
      @Override protected MongoDatabase doGetMongoDatabase(String name) {
        return super.doGetMongoDatabase(name).withReadConcern(ReadConcern.MAJORITY)
            .withWriteConcern(WriteConcern.MAJORITY.withJournal(true)).withReadPreference(ReadPreference.primary());
      }
    };
    template = new MongoTemplate(factory, shared.getConverter());
    template.setWriteConcern(WriteConcern.MAJORITY.withJournal(true));
    template.setReadPreference(ReadPreference.primary());
  }
  /** Package-private fixture constructor; caller provides an isolated namespace/concern-configured template. */
  ComplaintMongoStore(MongoTemplate isolated) { template = isolated; }
  public MongoTemplate template() { return template; }
  public String collectionName(Class<?> type) { return template.getCollectionName(type); }
}
