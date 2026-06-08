package com.argus.cases.repository;

import com.argus.cases.model.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLogEntry, Long> {
    List<AuditLogEntry> findTop100ByOrderByCreatedAtDesc();
}
