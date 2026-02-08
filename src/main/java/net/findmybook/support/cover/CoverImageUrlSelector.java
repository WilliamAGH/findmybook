package net.findmybook.support.cover;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * Selects the most appropriate canonical cover URL from event metadata.
 *
 * <p>This utility centralizes priority rules so event emission, upload orchestration,
 * and persistence paths stay aligned.</p>
 */
public final class CoverImageUrlSelector {

    private static final List<String> PRIORITY_ORDER = List.of(
        "canonical",
        "extraLarge",
        "large",
        "medium",
        "small",
        "thumbnail",
        "smallThumbnail"
    );

    private CoverImageUrlSelector() {
    }

    /**
     * Resolves a canonical image URL by preferring the explicit canonical value and then
     * falling back to provider-specific image-link priority ordering.
     *
     * @param canonicalImageUrl producer-provided canonical URL, when available
     * @param imageLinks provider image links keyed by image type
     * @return the selected URL, or {@code null} when no usable URL exists
     */
    @Nullable
    public static String resolveCanonicalImageUrl(@Nullable String canonicalImageUrl,
                                                  @Nullable Map<String, String> imageLinks) {
        if (StringUtils.hasText(canonicalImageUrl)) {
            return canonicalImageUrl;
        }
        return selectPreferredImageUrl(imageLinks);
    }

    /**
     * Applies deterministic image-link priority ordering and falls back to the first
     * non-blank value in the source map.
     *
     * @param imageLinks provider image links keyed by image type
     * @return preferred image URL, or {@code null} when no candidate is present
     */
    @Nullable
    public static String selectPreferredImageUrl(@Nullable Map<String, String> imageLinks) {
        if (imageLinks == null || imageLinks.isEmpty()) {
            return null;
        }

        for (String key : PRIORITY_ORDER) {
            String candidate = imageLinks.get(key);
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }

        return imageLinks.values().stream()
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse(null);
    }
}
