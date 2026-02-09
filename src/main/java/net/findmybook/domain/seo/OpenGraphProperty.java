package net.findmybook.domain.seo;

/**
 * Represents a single Open Graph property/value pair in the domain SEO model.
 *
 * <p>This value object keeps crawler-facing metadata explicit and strongly typed
 * across application and adapter boundaries.
 *
 * @param property Open Graph property key (for example {@code og:type} or {@code book:isbn})
 * @param content property value rendered in the page head
 */
public record OpenGraphProperty(String property, String content) {
}

