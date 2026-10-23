package com.company.labor.foundation.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

@Component
public class JsonCodec {
    private final ObjectMapper mapper;
    private final ObjectMapper canonical;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
        this.canonical = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    public String canonical(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON serialization failed", exception);
        }
    }

    public <T> T read(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }

    public <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid JSON", exception);
        }
    }
}
