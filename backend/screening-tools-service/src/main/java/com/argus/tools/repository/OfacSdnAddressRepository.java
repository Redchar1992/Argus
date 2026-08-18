package com.argus.tools.repository;

import com.argus.tools.model.OfacSdnAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OfacSdnAddressRepository extends JpaRepository<OfacSdnAddress, Long> {
    List<OfacSdnAddress> findByNormalizedAddress(String normalizedAddress);
}
