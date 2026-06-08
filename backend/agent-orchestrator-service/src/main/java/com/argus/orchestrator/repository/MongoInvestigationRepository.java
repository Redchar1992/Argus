package com.argus.orchestrator.repository;

import com.argus.orchestrator.model.Investigation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data Mongo repository for investigation traces. Only instantiated when
 * argus.trace.store=mongo, so the default in-memory profile needs no Mongo driver
 * connection.
 */
@ConditionalOnProperty(name = "argus.trace.store", havingValue = "mongo")
public interface MongoInvestigationRepository extends MongoRepository<Investigation, String> {
}
