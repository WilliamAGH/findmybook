package net.findmybook.application.seo;

import java.util.regex.Pattern;
import net.findmybook.util.SeoUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Normalizes model output into contract-safe SEO title and description values.
 */
@Component
class SeoMetadataNormalizationPolicy {

    private static final int TARGET_TITLE_LENGTH = 60;
    private static final int TARGET_DESCRIPTION_LENGTH = 160;
    private static final String TITLE_SUFFIX = " - Book Details | findmybook.net";
    private static final Pattern EXPECTED_TITLE_PATTERN =
        Pattern.compile("^.+ - Book Details \\| findmybook\\.net$", Pattern.CASE_INSENSITIVE);

    /**
     * Normalizes a candidate SEO title, falling back to deterministic output when invalid.
     */
    String normalizeSeoTitle(String candidateTitle, String fallbackBookTitle) {
        String fallbackTitle = buildDeterministicTitle(fallbackBookTitle);
        if (!StringUtils.hasText(candidateTitle)) {
            return fallbackTitle;
        }
        String normalized = candidateTitle.replaceAll("\\s+", " ").trim();
        if (!EXPECTED_TITLE_PATTERN.matcher(normalized).matches() || normalized.length() > TARGET_TITLE_LENGTH) {
            return fallbackTitle;
        }
        return normalized;
    }

    /**
     * Normalizes a candidate SEO description, falling back to deterministic output when invalid.
     */
    String normalizeSeoDescription(String candidateDescription, String fallbackDescriptionSource) {
        String fallbackDescription = buildDeterministicDescription(fallbackDescriptionSource);
        if (!StringUtils.hasText(candidateDescription)) {
            return fallbackDescription;
        }
        String plainDescription = candidateDescription
            .replaceAll("<[^>]*>", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (!StringUtils.hasText(plainDescription)) {
            return fallbackDescription;
        }
        return SeoUtils.truncateDescription(plainDescription, TARGET_DESCRIPTION_LENGTH);
    }

    /**
     * Builds a deterministic title that satisfies the route format contract.
     */
    String buildDeterministicTitle(String rawBookTitle) {
        String title = StringUtils.hasText(rawBookTitle) ? rawBookTitle.trim() : "Book";
        int titleBudget = Math.max(10, TARGET_TITLE_LENGTH - TITLE_SUFFIX.length());
        String normalizedTitle = truncateForTitle(title, titleBudget);
        return normalizedTitle + TITLE_SUFFIX;
    }

    /**
     * Builds a deterministic description from source content.
     */
    String buildDeterministicDescription(String sourceDescription) {
        return SeoUtils.truncateDescription(sourceDescription, TARGET_DESCRIPTION_LENGTH);
    }

    private String truncateForTitle(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        String shortened = value.substring(0, maxLength - 3);
        int lastSpace = shortened.lastIndexOf(' ');
        if (lastSpace > 5) {
            shortened = shortened.substring(0, lastSpace);
        }
        return shortened + "...";
    }
}
