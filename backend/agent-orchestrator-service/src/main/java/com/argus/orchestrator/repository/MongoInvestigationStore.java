package com.argus.orchestrator.repository;

import com.argus.orchestrator.model.Investigation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * MongoDB-backed trace store (real NoSQL). Active when argus.trace.store=mongo.
 */
@Repository
@ConditionalOnProperty(name = "argus.trace.store", havingValue = "mongo")
public class MongoInvestigationStore implements InvestigationStore {

    private final MongoInvestigationRepository repository;

    public MongoInvestigationStore(MongoInvestigationRepository repository) {
        this.repository = repository;
    }

    @Override
    public Investigation save(Investigation investigation) {
        return repository.save(investigation);
    }

    @Override
    public Optional<Investigation> findById(String id) {
        return repository.findById(id);
    }

    @Override
    public List<Investigation> findRecent(int limit) {
        return repository.findAll(
                        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent();
    }
}
