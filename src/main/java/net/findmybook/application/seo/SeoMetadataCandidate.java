package net.findmybook.application.seo;

/**
 * Typed SEO metadata candidate emitted by parser/client layers.
 *
 * @param seoTitle candidate title from model output
 * @param seoDescription candidate description from model output
 */
record SeoMetadataCandidate(String seoTitle, String seoDescription) {
}
