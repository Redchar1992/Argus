package com.argus.orchestrator.repository;

import com.argus.orchestrator.model.Investigation;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for investigations + their traces. Two implementations:
 *   - MongoInvestigationStore  (real NoSQL, active when argus.trace.store=mongo)
 *   - InMemoryInvestigationStore (default; lets the demo run with zero infra)
 */
public interface InvestigationStore {

    Investigation save(Investigation investigation);

    Optional<Investigation> findById(String id);

    List<Investigation> findRecent(int limit);
}
