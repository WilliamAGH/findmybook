package net.findmybook.application.seo;

import java.util.Optional;
import org.springframework.util.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses raw LLM JSON responses into typed SEO metadata fields.
 */
class SeoMetadataJsonParser {

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
        String seoTitle = requiredText(payload, "seoTitle", "seo_title", "title");
        String seoDescription = requiredText(payload, "seoDescription", "seo_description", "description", "metaDescription");
        return new SeoMetadataCandidate(seoTitle, seoDescription);
    }

    private JsonNode parseJsonPayload(String responseText) {
        String cleaned = responseText.replace("```json", "").replace("```", "").trim();
        try {
            return objectMapper.readTree(cleaned);
        } catch (JacksonException initialParseException) {
            int openBrace = cleaned.indexOf('{');
            int closeBrace = cleaned.lastIndexOf('}');
            if (openBrace < 0 || closeBrace <= openBrace) {
                throw invalidResponse("SEO metadata response did not include a valid JSON object");
            }
            String extracted = cleaned.substring(openBrace, closeBrace + 1);
            try {
                return objectMapper.readTree(extracted);
            } catch (JacksonException parseException) {
                throw invalidResponse("SEO metadata response JSON parsing failed", parseException);
            }
        }
    }

    private String requiredText(JsonNode payload, String field, String... aliases) {
        return optionalText(payload, field, aliases)
            .orElseThrow(() -> invalidResponse("SEO metadata response missing required field: " + field));
    }

    private BookSeoGenerationException invalidResponse(String message) {
        return new BookSeoGenerationException(BookSeoGenerationException.ErrorCode.INVALID_RESPONSE, message);
    }

    private BookSeoGenerationException invalidResponse(String message, JacksonException cause) {
        return new BookSeoGenerationException(BookSeoGenerationException.ErrorCode.INVALID_RESPONSE, message, cause);
    }

    private Optional<String> optionalText(JsonNode payload, String field, String... aliases) {
        JsonNode fieldNode = payload.get(field);
        if (fieldNode != null && !fieldNode.isNull()) {
            String fieldValue = fieldNode.asString();
            if (StringUtils.hasText(fieldValue)) {
                return Optional.of(fieldValue.trim());
            }
        }
        for (String alias : aliases) {
            JsonNode aliasNode = payload.get(alias);
            if (aliasNode != null && !aliasNode.isNull()) {
                String aliasValue = aliasNode.asString();
                if (StringUtils.hasText(aliasValue)) {
                    return Optional.of(aliasValue.trim());
                }
            }
        }
        return Optional.empty();
    }

}
