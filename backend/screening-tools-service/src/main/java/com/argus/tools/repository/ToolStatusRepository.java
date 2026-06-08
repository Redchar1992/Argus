package com.argus.tools.repository;

import com.argus.tools.model.ToolStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolStatusRepository extends JpaRepository<ToolStatus, String> {
}
