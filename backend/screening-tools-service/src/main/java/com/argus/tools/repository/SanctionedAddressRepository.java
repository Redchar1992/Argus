package com.argus.tools.repository;

import com.argus.tools.model.SanctionedAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SanctionedAddressRepository extends JpaRepository<SanctionedAddress, String> {
    List<SanctionedAddress> findByAddressIn(Collection<String> addresses);
}
