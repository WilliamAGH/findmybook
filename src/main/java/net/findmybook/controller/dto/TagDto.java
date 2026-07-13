package net.findmybook.controller.dto;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** DTO representing a qualifier/tag assignment. */
public record TagDto(String key, Map<String, Serializable> attributes) {
    public TagDto {
        attributes = attributes == null || attributes.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }
}
