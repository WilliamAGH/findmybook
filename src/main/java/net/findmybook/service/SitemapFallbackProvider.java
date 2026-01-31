package net.findmybook.service;

import java.util.Optional;

/**
 * Supplies fallback sitemap data when the primary Postgres datasource is unavailable.
 */
public interface SitemapFallbackProvider {

    /**
     * Load the latest available sitemap snapshot.
     *
     * @return optional snapshot when a fallback source (e.g., S3) is available
     */
    Optional<SitemapService.FallbackSnapshot> loadSnapshot();
}

