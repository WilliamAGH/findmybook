package net.findmybook.support.seo;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates crawler-facing SEO/OG metadata in development mode.
 *
 * <p>The validator is non-blocking by design: it emits actionable warnings for
 * invalid or suboptimal metadata and returns findings to callers, but never
 * changes runtime behavior or response payloads.
 */
@Component
public class SeoMetadataDevValidator {

    private static final Logger log = LoggerFactory.getLogger(SeoMetadataDevValidator.class);

    private static final int RECOMMENDED_TITLE_LENGTH = 60;
    private static final int RECOMMENDED_DESCRIPTION_LENGTH = 160;
    private static final int MIN_IMAGE_WIDTH = 144;
    private static final int MIN_IMAGE_HEIGHT = 144;
    private static final int RECOMMENDED_IMAGE_WIDTH = 1200;
    private static final int RECOMMENDED_IMAGE_HEIGHT = 630;
    private static final double MIN_ASPECT_RATIO = 1.85d;
    private static final double MAX_ASPECT_RATIO = 2.10d;
    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of(
        "image/png",
        "image/jpeg",
        "image/gif",
        "image/webp"
    );

    private final Environment environment;
    private final boolean enabled;

    public static SeoMetadataDevValidator disabled() {
        return new SeoMetadataDevValidator(null, false);
    }

    @Autowired
    public SeoMetadataDevValidator(Environment environment) {
        this(environment, true);
    }

    private SeoMetadataDevValidator(Environment environment, boolean enabled) {
        this.environment = environment;
        this.enabled = enabled;
    }

    /**
     * Validates route metadata and returns all development warnings.
     *
     * @param title rendered page title
     * @param description rendered meta description
     * @param canonicalUrl canonical absolute URL
     * @param openGraphImage absolute OpenGraph image URL
     * @param openGraphType OpenGraph object type
     * @param siteName OpenGraph site name
     * @param openGraphImageType MIME type declared in OG tags
     * @param openGraphImageWidth declared OG image width
     * @param openGraphImageHeight declared OG image height
     * @param twitterCard twitter card type value
     * @return development warnings; empty when valid or validation disabled
     */
    public List<String> validateSpaHead(String title,
                                        String description,
                                        String canonicalUrl,
                                        String openGraphImage,
                                        String openGraphType,
                                        String siteName,
                                        String openGraphImageType,
                                        int openGraphImageWidth,
                                        int openGraphImageHeight,
                                        String twitterCard) {
        if (!shouldValidate()) {
            return List.of();
        }

        List<String> warnings = new ArrayList<>();
        validateRequiredField("title", title, warnings);
        validateRequiredField("description", description, warnings);
        validateRequiredField("og:type", openGraphType, warnings);
        validateRequiredField("og:site_name", siteName, warnings);
        validateRequiredField("twitter:card", twitterCard, warnings);

        validateAbsolutePublicHttpsUrl("canonical URL", canonicalUrl, warnings);
        validateAbsolutePublicHttpsUrl("og:image", openGraphImage, warnings);
        validateTextLengths(title, description, warnings);
        validateImageMetadata(openGraphImageType, openGraphImageWidth, openGraphImageHeight, warnings);

        if (!warnings.isEmpty()) {
            String route = StringUtils.hasText(canonicalUrl) ? canonicalUrl : "(missing canonical URL)";
            log.warn("SEO metadata validation warnings for {}: {}", route, String.join(" | ", warnings));
        }
        return warnings;
    }

    private boolean shouldValidate() {
        return enabled && environment != null && environment.acceptsProfiles(Profiles.of("dev"));
    }

    private void validateRequiredField(String fieldName, String value, List<String> warnings) {
        if (!StringUtils.hasText(value)) {
            warnings.add(fieldName + " is required but blank");
        }
    }

    private void validateAbsolutePublicHttpsUrl(String label, String url, List<String> warnings) {
        if (!StringUtils.hasText(url)) {
            warnings.add(label + " must be an absolute https URL, but it is blank");
            return;
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException invalidUri) {
            warnings.add(label + " is not a valid URI: " + url);
            return;
        }
        if (!uri.isAbsolute()) {
            warnings.add(label + " must be absolute, received: " + url);
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            warnings.add(label + " should use https, received scheme: " + uri.getScheme());
        }
        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            warnings.add(label + " is missing host: " + url);
            return;
        }
        if ("localhost".equalsIgnoreCase(host)
            || host.startsWith("127.")
            || "0.0.0.0".equals(host)
            || "::1".equals(host)) {
            warnings.add(label + " must be publicly reachable, received local host: " + host);
        }
    }

    private void validateTextLengths(String title, String description, List<String> warnings) {
        if (StringUtils.hasText(title) && title.trim().length() > RECOMMENDED_TITLE_LENGTH) {
            warnings.add("title exceeds recommended " + RECOMMENDED_TITLE_LENGTH + " characters");
        }
        if (StringUtils.hasText(description) && description.trim().length() > RECOMMENDED_DESCRIPTION_LENGTH) {
            warnings.add("description exceeds recommended " + RECOMMENDED_DESCRIPTION_LENGTH + " characters");
        }
    }

    private void validateImageMetadata(String imageType, int width, int height, List<String> warnings) {
        if (!StringUtils.hasText(imageType)) {
            warnings.add("og:image:type is required but blank");
        } else if (!SUPPORTED_IMAGE_TYPES.contains(imageType.trim().toLowerCase())) {
            warnings.add("og:image:type is not in supported set: " + imageType);
        }

        if (width < MIN_IMAGE_WIDTH || height < MIN_IMAGE_HEIGHT) {
            warnings.add("og:image dimensions are below minimum " + MIN_IMAGE_WIDTH + "x" + MIN_IMAGE_HEIGHT);
        }
        if (width < RECOMMENDED_IMAGE_WIDTH || height < RECOMMENDED_IMAGE_HEIGHT) {
            warnings.add("og:image dimensions are below recommended " + RECOMMENDED_IMAGE_WIDTH + "x" + RECOMMENDED_IMAGE_HEIGHT);
        }
        if (height <= 0) {
            warnings.add("og:image:height must be greater than zero");
            return;
        }

        double aspectRatio = (double) width / (double) height;
        if (aspectRatio < MIN_ASPECT_RATIO || aspectRatio > MAX_ASPECT_RATIO) {
            warnings.add("og:image aspect ratio %.2f is outside %.2f-%.2f"
                .formatted(aspectRatio, MIN_ASPECT_RATIO, MAX_ASPECT_RATIO));
        }
    }
}
