package net.findmybook.mapper;

import org.springframework.lang.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Package-private static utilities for extracting typed values from Google Books JSON nodes.
 */
final class GoogleBooksJsonSupport {

    private GoogleBooksJsonSupport() {
    }

    @Nullable
    static String getTextValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        if (!fieldNode.isTextual()) {
            return null;
        }

        String value = fieldNode.asText();
        if (value == null || value.isEmpty()) {
            return value;
        }

        // Remove leading and trailing quotes (handles "value" -> value)
        // This protects against malformed JSON where text fields contain literal quotes
        return value.replaceAll("^\"|\"$", "");
    }

    @Nullable
    static Integer getIntValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        return fieldNode.isInt() ? fieldNode.asInt() : null;
    }

    @Nullable
    static Double getDoubleValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        return fieldNode.isNumber() ? fieldNode.asDouble() : null;
    }

    @Nullable
    static Boolean getBooleanValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        return fieldNode.isBoolean() ? fieldNode.asBoolean() : null;
    }
}
