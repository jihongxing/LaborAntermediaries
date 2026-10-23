package com.company.labor.foundation.audit;

import com.company.labor.foundation.context.ExecutionContext;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AuditRecorder {
    private final JdbcTemplate jdbc;

    public AuditRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String resourceType, String resourceId, String outcome) {
        requireIdentifier(action, 100);
        requireIdentifier(resourceType, 100);
        requireIdentifier(resourceId, 200);
        if (!Set.of("SUCCESS", "DENIED", "FAILED").contains(outcome)) {
            throw new IllegalArgumentException("Invalid audit outcome");
        }
        ExecutionContext.Identity identity = ExecutionContext.require();
        jdbc.update("""
                INSERT INTO foundation_audit(actor_id, tenant_id, action, resource_type, resource_id, outcome, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, identity.actorId(), identity.tenantId(), action, resourceType, resourceId, outcome, MDC.get("requestId"));
    }

    private void requireIdentifier(String value, int maximum) {
        if (value == null || !value.matches("[a-zA-Z0-9:._-]{1," + maximum + "}")) {
            throw new IllegalArgumentException("Audit fields must contain identifiers, not arbitrary sensitive content");
        }
    }
}
