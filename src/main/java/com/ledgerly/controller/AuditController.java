package com.ledgerly.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerly.dto.AuditEventResponse;
import com.ledgerly.service.AuditService;

/** The audit trail, for managers (see SecurityConfig). */
@RestController
public class AuditController {

	private final AuditService auditService;

	public AuditController(AuditService auditService) {
		this.auditService = auditService;
	}

	/**
	 * With entityType and entityId (e.g. TRANSACTION and TX-7KQ2MZ4H): that record's whole history,
	 * oldest first. Without them: the latest 100 events, newest first.
	 */
	@GetMapping("/api/audit")
	public List<AuditEventResponse> audit(@RequestParam(required = false) String entityType,
			@RequestParam(required = false) String entityId) {
		return auditService.search(entityType, entityId);
	}

}
