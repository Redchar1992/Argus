package com.argus.tools.repository;

import com.argus.tools.model.TransactionEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TransactionEdgeRepository extends JpaRepository<TransactionEdge, Long> {

    /** Outgoing + incoming edges for a frontier of addresses (graph walk one hop). */
    List<TransactionEdge> findByFromAddressInOrToAddressIn(
            Collection<String> fromAddresses, Collection<String> toAddresses);
}
