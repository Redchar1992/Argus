package com.argus.cases.repository;

import com.argus.cases.model.CaseRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CaseRecordRepository extends JpaRepository<CaseRecord, String> {
    List<CaseRecord> findTop50ByOrderByCreatedAtDesc();
}
