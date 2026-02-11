package net.findmybook.support.seo;

/**
 * Typed Open Graph property/value pair for rendering social metadata tags.
 *
 * @param property Open Graph property name (for example {@code og:type} or {@code book:isbn})
 * @param content property value
 */
public record OpenGraphProperty(String property, String content) {
}

