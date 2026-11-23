package com.ledgerly.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerly.entity.AuditEvent;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

	List<AuditEvent> findByEntityTypeAndEntityIdOrderByCreatedAtAsc(String entityType, String entityId);

	List<AuditEvent> findTop100ByOrderByIdDesc();

}
