package net.findmybook.application.seo;

/**
 * Centralized SEO presentation constants shared across SEO application use cases.
 *
 * <p>Keeping these values in one place avoids duplicated literals and makes the
 * SEO contract explicit across route metadata and shell-rendering workflows.
 */
public final class SeoPresentationDefaults {

    /** Canonical public brand token used in metadata and shell rendering. */
    public static final String BRAND_NAME = "findmybook";
    /** Global suffix appended to rendered page titles. */
    public static final String PAGE_TITLE_SUFFIX = " | " + BRAND_NAME;
    /** Default route description when route-specific metadata is absent. */
    public static final String DEFAULT_DESCRIPTION =
        "Discover your next favorite read with findmybook recommendations, search, and curated collections.";
    /** Default route keywords when route-specific metadata is absent. */
    public static final String DEFAULT_KEYWORDS = "findmybook, book recommendations, book discovery, book search";
    /** Default robots directive for indexable pages. */
    public static final String ROBOTS_INDEX_FOLLOW = "index, follow, max-image-preview:large";
    /** Robots directive for searchable utility pages that should not index. */
    public static final String ROBOTS_SEARCH_NOINDEX_FOLLOW = "noindex, follow, max-image-preview:large";
    /** Robots directive for terminal/error pages. */
    public static final String ROBOTS_NOINDEX_NOFOLLOW = "noindex, nofollow, noarchive";
    /** Default Open Graph type for non-book pages. */
    public static final String OPEN_GRAPH_TYPE_WEBSITE = "website";
    /** Open Graph type for book detail routes. */
    public static final String OPEN_GRAPH_TYPE_BOOK = "book";
    /** Open Graph/Twitter image alt text. */
    public static final String OPEN_GRAPH_IMAGE_ALT = "findmybook social preview image";
    /** Default browser/theme color used by rendered shells. */
    public static final String THEME_COLOR = "#fdfcfa";
    /** Canonical not-found route used for SEO canonical normalization. */
    public static final String DEFAULT_NOT_FOUND_PATH = "/404";
    /** Canonical error route used for SEO canonical normalization. */
    public static final String DEFAULT_ERROR_PATH = "/error";

    private SeoPresentationDefaults() {
    }
}

