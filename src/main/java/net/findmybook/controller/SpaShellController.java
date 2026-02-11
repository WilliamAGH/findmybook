package net.findmybook.controller;

import net.findmybook.domain.seo.SeoMetadata;
import net.findmybook.service.BookSeoMetadataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Shared base for controllers that serve server-rendered HTML shells with
 * route-specific SEO metadata ({@code <head>} tags, OpenGraph, Twitter cards)
 * and the Svelte SPA mount point.
 *
 * <p>Each subclass maps explicit routes and resolves its own
 * {@link SeoMetadata}; this class provides the common
 * render-to-{@link org.springframework.http.ResponseEntity} step.
 *
 * @see BookSeoMetadataService#renderSpaShell(SeoMetadata)
 */
abstract class SpaShellController {

    protected final BookSeoMetadataService bookSeoMetadataService;

    protected SpaShellController(BookSeoMetadataService bookSeoMetadataService) {
        this.bookSeoMetadataService = bookSeoMetadataService;
    }

    /** Renders the SEO-enriched HTML shell and wraps it in an {@link ResponseEntity}. */
    protected ResponseEntity<String> spaResponse(SeoMetadata metadata,
                                                 HttpStatus status) {
        String html = bookSeoMetadataService.renderSpaShell(metadata);
        return ResponseEntity.status(status)
            .contentType(MediaType.TEXT_HTML)
            .body(html);
    }
}
