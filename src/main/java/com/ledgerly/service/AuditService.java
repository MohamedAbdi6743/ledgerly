package com.ledgerly.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.AuditEvent;
import com.ledgerly.repository.AuditEventRepository;

/** Writes the audit trail. Called from other services, inside their transaction. */
@Service
public class AuditService {

	private final AuditEventRepository auditEventRepository;
	private final Clock clock;

	public AuditService(AuditEventRepository auditEventRepository, Clock clock) {
		this.auditEventRepository = auditEventRepository;
		this.clock = clock;
	}

	/**
	 * MANDATORY: an audit line must be part of the same transaction as the change it describes,
	 * so either both are saved or neither is. Calling this outside a transaction is a bug.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(AppUser actor, String action, String entityType, String entityId, String details) {
		auditEventRepository.save(new AuditEvent(actor, action, entityType, entityId, details, Instant.now(clock)));
	}

}
