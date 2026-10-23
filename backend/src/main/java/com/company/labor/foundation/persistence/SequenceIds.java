package com.company.labor.foundation.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SequenceIds {
    private final JdbcTemplate jdbc;

    public SequenceIds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long nextId() {
        Long value = jdbc.queryForObject("SELECT nextval('foundation_id_seq')", Long.class);
        if (value == null) {
            throw new IllegalStateException("Identifier sequence returned no value");
        }
        return value;
    }

    public String nextReference(String prefix) {
        if (prefix == null || !prefix.matches("[A-Z][A-Z0-9]{0,15}")) {
            throw new IllegalArgumentException("Reference prefix must contain 1-16 uppercase letters or digits");
        }
        return prefix + "-" + nextId();
    }
}
