package net.findmybook.controller;

import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.util.ApplicationConstants;
import org.springframework.util.StringUtils;
import net.findmybook.util.cover.CoverUrlResolver;
import net.findmybook.util.cover.UrlSourceDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Lightweight cover endpoint backed by Postgres projections and resolver utilities.
 * Eliminates the legacy cover orchestration pipeline and surfaces the canonical
 * `CoverUrlResolver` output for clients that still call `/api/covers/{id}`.
 */
@RestController
@RequestMapping("/api/covers")
@Slf4j
public class BookCoverController {

    private static final String SOURCE_S3_CACHE = "S3_CACHE";
    private static final String SOURCE_UNDEFINED = "UNDEFINED";

    private final BookSearchService bookSearchService;
    private final BookIdentifierResolver bookIdentifierResolver;
    /**
     * The book data orchestrator, may be null when disabled.
     */
    private final BookDataOrchestrator bookDataOrchestrator;

    /**
     * Constructs BookCoverController with optional orchestrator.
     *
     * @param bookSearchService the book search service
     * @param bookIdentifierResolver the identifier resolver
     * @param bookDataOrchestrator the data orchestrator, or null when disabled
     */
    public BookCoverController(BookSearchService bookSearchService,
                               BookIdentifierResolver bookIdentifierResolver,
                               BookDataOrchestrator bookDataOrchestrator) {
        this.bookSearchService = bookSearchService;
        this.bookIdentifierResolver = bookIdentifierResolver;
        this.bookDataOrchestrator = bookDataOrchestrator;
    }

    @GetMapping("/{identifier}")
    public ResponseEntity<CoverResponse> getCover(@PathVariable String identifier,
                                                   @RequestParam(required = false, defaultValue = "ANY") String sourcePreference) {
        CoverPayload payload;
        try {
            payload = resolveCover(identifier)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found with ID: " + identifier));
        } catch (IllegalStateException ex) {
            log.error("Cover resolution failed for '{}': {}", identifier, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Cover resolution failed for '" + identifier + "'", ex);
        }

        CoverDetail cover = new CoverDetail(
            payload.cover().url(),
            payload.fallbackUrl(),
            payload.sourceLabel(),
            payload.cover().width(),
            payload.cover().height(),
            payload.cover().highResolution(),
            sourcePreference
        );

        CoverResponse response = new CoverResponse(
            payload.bookId(),
            sourcePreference,
            cover,
            payload.cover().url(),
            payload.cover().url(),
            payload.fallbackUrl()
        );

        return ResponseEntity.ok(response);
    }

    private Optional<CoverPayload> resolveCover(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Optional.empty();
        }

        Optional<CoverPayload> fromSlug = bookSearchService.fetchBookDetailBySlug(identifier)
            .map(detail -> toPayload(detail.id(), detail.coverUrl(), detail.thumbnailUrl(), detail.coverWidth(), detail.coverHeight(), detail.coverHighResolution()));
        if (fromSlug.isPresent()) {
            return fromSlug;
        }

        Optional<UUID> maybeUuid = bookIdentifierResolver.resolveToUuid(identifier);
        if (maybeUuid.isPresent()) {
            Optional<CoverPayload> fromUuid = bookSearchService.fetchBookDetail(maybeUuid.get())
                .map(detail -> toPayload(detail.id(), detail.coverUrl(), detail.thumbnailUrl(), detail.coverWidth(), detail.coverHeight(), detail.coverHighResolution()));
            if (fromUuid.isPresent()) {
                return fromUuid;
            }
        }

        if (bookDataOrchestrator == null) {
            return Optional.empty();
        }

        return bookDataOrchestrator.fetchCanonicalBookReactive(identifier)
            .timeout(Duration.ofSeconds(5))
            .onErrorMap(ex -> {
                log.warn("Cover orchestrator lookup failed for '{}': {}", identifier, ex.getMessage());
                return new IllegalStateException("Cover orchestrator lookup failed for identifier '" + identifier + "'", ex);
            })
            .blockOptional()
            .map(book -> toPayload(
                book.getId(),
                book.getS3ImagePath(),
                book.getExternalImageUrl(),
                book.getCoverImageWidth(),
                book.getCoverImageHeight(),
                book.getIsCoverHighResolution()
            ));
    }

    private CoverPayload toPayload(String bookId,
                                   String primaryUrl,
                                   String fallbackCandidate,
                                   Integer width,
                                   Integer height,
                                   Boolean highRes) {
        CoverUrlResolver.ResolvedCover resolved = CoverUrlResolver.resolve(
            primaryUrl,
            fallbackCandidate,
            width,
            height,
            highRes
        );

        String fallbackUrl = firstNonBlank(fallbackCandidate, primaryUrl, ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH);
        String sourceLabel = resolved.fromS3()
            ? SOURCE_S3_CACHE
            : Optional.ofNullable(UrlSourceDetector.detectSource(resolved.url()))
                .map(Enum::name)
                .orElse(SOURCE_UNDEFINED);

        return new CoverPayload(bookId, resolved, fallbackUrl, sourceLabel);
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH;
    }

    private record CoverPayload(String bookId,
                                CoverUrlResolver.ResolvedCover cover,
                                String fallbackUrl,
                                String sourceLabel) {}

    record CoverDetail(String preferredUrl,
                        String fallbackUrl,
                        String source,
                        Integer width,
                        Integer height,
                        Boolean highResolution,
                        String requestedSourcePreference) {}

    record CoverResponse(String bookId,
                          String requestedSourcePreference,
                          CoverDetail cover,
                          String coverUrl,
                          String preferredUrl,
                          String fallbackUrl) {}
}
