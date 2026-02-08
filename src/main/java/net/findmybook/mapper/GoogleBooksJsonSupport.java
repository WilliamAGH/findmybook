package net.findmybook.mapper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeType;

/**
 * Package-private static utilities for extracting typed values from Google Books JSON nodes.
 */
final class GoogleBooksJsonSupport {

    private GoogleBooksJsonSupport() {
    }

    static String getTextValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        if (fieldNode.getNodeType() != JsonNodeType.STRING) {
            return null;
        }

        String value = fieldNode.asString();
        if (value == null || value.isEmpty()) {
            return value;
        }

        // Remove leading and trailing quotes (handles "value" -> value)
        // This protects against malformed JSON where text fields contain literal quotes
        return value.replaceAll("^\"|\"$", "");
    }

    static Integer getIntValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        return fieldNode.isInt() ? fieldNode.asInt() : null;
    }

    static Double getDoubleValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        return fieldNode.isNumber() ? fieldNode.asDouble() : null;
    }

    static Boolean getBooleanValue(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        JsonNode fieldNode = node.get(field);
        return fieldNode.isBoolean() ? fieldNode.asBoolean() : null;
    }
}
