package net.findmybook.application.seo;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses raw LLM JSON responses into typed SEO metadata fields.
 */
class SeoMetadataJsonParser {

    private static final Set<String> CANONICAL_FIELDS = Arrays.stream(SeoMetadataCandidate.class.getRecordComponents())
        .map(recordComponent -> recordComponent.getName())
        .collect(Collectors.toUnmodifiableSet());

    private final ObjectMapper objectMapper;

    SeoMetadataJsonParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a model response into SEO title/description fields.
     *
     * @param responseText raw LLM response text
     * @return parsed SEO metadata values
     */
    SeoMetadataCandidate parse(String responseText) {
        if (!StringUtils.hasText(responseText)) {
            throw invalidResponse("SEO metadata response was empty");
        }
        JsonNode payload = parseJsonPayload(responseText);
        validateCanonicalFields(payload);
        String seoTitle = requiredText(payload, "seoTitle");
        String seoDescription = requiredText(payload, "seoDescription");
        return new SeoMetadataCandidate(seoTitle, seoDescription);
    }

    private JsonNode parseJsonPayload(String responseText) {
        try {
            JsonNode payload = objectMapper.readTree(responseText.trim());
            if (!payload.isObject()) {
                throw invalidResponse("SEO metadata response must be a JSON object");
            }
            return payload;
        } catch (JacksonException parseException) {
            throw invalidResponse("SEO metadata response did not include a valid JSON object", parseException);
        }
    }

    private String requiredText(JsonNode payload, String field) {
        return optionalText(payload, field)
            .orElseThrow(() -> invalidResponse("SEO metadata response missing required field: " + field));
    }

    private BookSeoGenerationException invalidResponse(String message) {
        return new BookSeoGenerationException(BookSeoGenerationException.ErrorCode.INVALID_RESPONSE, message);
    }

    private BookSeoGenerationException invalidResponse(String message, JacksonException cause) {
        return new BookSeoGenerationException(BookSeoGenerationException.ErrorCode.INVALID_RESPONSE, message, cause);
    }

    private Optional<String> optionalText(JsonNode payload, String field) {
        JsonNode fieldNode = payload.get(field);
        if (!fieldNode.isString()) {
            throw invalidResponse("SEO metadata response field must be a string: " + field);
        }
        String fieldValue = fieldNode.stringValue();
        if (StringUtils.hasText(fieldValue)) {
            return Optional.of(fieldValue.trim());
        }
        return Optional.empty();
    }

    private void validateCanonicalFields(JsonNode payload) {
        Set<String> responseFields = Set.copyOf(payload.propertyNames());
        if (!responseFields.equals(CANONICAL_FIELDS)) {
            throw invalidResponse("SEO metadata response fields must exactly match the canonical contract: " + CANONICAL_FIELDS);
        }
    }

}
