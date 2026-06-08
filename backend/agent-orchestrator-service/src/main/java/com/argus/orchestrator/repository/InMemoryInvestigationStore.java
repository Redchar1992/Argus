package com.argus.orchestrator.repository;

import com.argus.orchestrator.model.Investigation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default trace store: thread-safe in-memory map. Lets the whole agent demo run
 * with no MongoDB. Activated unless argus.trace.store=mongo is set.
 */
@Repository
@ConditionalOnProperty(name = "argus.trace.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryInvestigationStore implements InvestigationStore {

    private final ConcurrentMap<String, Investigation> store = new ConcurrentHashMap<>();

    @Override
    public Investigation save(Investigation investigation) {
        store.put(investigation.getId(), investigation);
        return investigation;
    }

    @Override
    public Optional<Investigation> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Investigation> findRecent(int limit) {
        return store.values().stream()
                .sorted(Comparator.comparing(Investigation::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }
}
