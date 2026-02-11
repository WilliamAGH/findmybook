package net.findmybook.support.seo;

import net.findmybook.model.Book;

/**
 * Encapsulates all parameters needed to render a Book JSON-LD graph.
 *
 * <p>Replaces the 8-parameter {@code renderBookGraph} method signature on
 * {@link BookStructuredDataRenderer} with a single typed boundary object.
 *
 * @param book canonical book metadata
 * @param canonicalUrl canonical public URL for the book route
 * @param webPageTitle full rendered page title (brand-suffixed)
 * @param bookTitle fallback-safe book title used for {@code Book.name}
 * @param fallbackDescription route-level description fallback when the book has none
 * @param ogImage absolute Open Graph preview image URL
 * @param brandName website brand name for the WebSite entity
 * @param baseUrl canonical site base URL for graph identifiers
 */
public record BookGraphRenderRequest(
    Book book,
    String canonicalUrl,
    String webPageTitle,
    String bookTitle,
    String fallbackDescription,
    String ogImage,
    String brandName,
    String baseUrl
) {}
