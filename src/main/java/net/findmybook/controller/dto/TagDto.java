package net.findmybook.controller.dto;

import java.util.Map;

/** DTO representing a qualifier/tag assignment. */
public record TagDto(String key, Map<String, Object> attributes) {
}
