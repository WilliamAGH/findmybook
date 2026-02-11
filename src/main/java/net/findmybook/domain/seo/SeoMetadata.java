package net.findmybook.domain.seo;

import java.util.List;

/**
 * Canonical SEO metadata contract used by application use cases and web adapters.
 *
 * <p>This contract captures the full route-level SEO surface (title, canonical,
 * robots, Open Graph extensions, and JSON-LD) as a single immutable value object.
 *
 * @param title route title without global suffix
 * @param description route description text
 * @param canonicalUrl canonical public URL for the route
 * @param keywords comma-separated keyword list
 * @param ogImage absolute Open Graph image URL
 * @param robots robots directive for crawlers
 * @param openGraphType Open Graph object type (for example {@code website} or {@code book})
 * @param openGraphProperties additional route-specific Open Graph properties
 * @param structuredDataJson JSON-LD payload rendered in the route head
 */
public record SeoMetadata(
    String title,
    String description,
    String canonicalUrl,
    String keywords,
    String ogImage,
    String robots,
    String openGraphType,
    List<OpenGraphProperty> openGraphProperties,
    String structuredDataJson
) {

    private static final String DEFAULT_OPEN_GRAPH_TYPE = "website";

    /**
     * Creates SEO metadata with default Open Graph type and no extension metadata.
     *
     * @param title route title without global suffix
     * @param description route description text
     * @param canonicalUrl canonical public URL for the route
     * @param keywords comma-separated keyword list
     * @param ogImage absolute Open Graph image URL
     * @param robots robots directive for crawlers
     */
    public SeoMetadata(String title,
                       String description,
                       String canonicalUrl,
                       String keywords,
                       String ogImage,
                       String robots) {
        this(title, description, canonicalUrl, keywords, ogImage, robots, DEFAULT_OPEN_GRAPH_TYPE, List.of(), "");
    }

    /**
     * Normalizes nullable optional fields into explicit immutable defaults.
     */
    public SeoMetadata {
        openGraphType = hasText(openGraphType) ? openGraphType : DEFAULT_OPEN_GRAPH_TYPE;
        openGraphProperties = openGraphProperties == null ? List.of() : List.copyOf(openGraphProperties);
        structuredDataJson = structuredDataJson == null ? "" : structuredDataJson;
    }

    private static boolean hasText(String candidate) {
        return candidate != null && !candidate.trim().isEmpty();
    }
}

