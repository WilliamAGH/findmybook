package net.findmybook.application.cover;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.service.BookSearchService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.cover.CoverUrlResolver;
import net.findmybook.util.cover.UrlSourceDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves canonical cover payloads and identifiers for book-cover workflows.
 *
 * <p>This service centralizes identifier and cover lookup so controller endpoints and
 * ingestion use cases share one consistent source of truth.</p>
 */
@Service
public class BookCoverResolutionService {

    private static final Logger log = LoggerFactory.getLogger(BookCoverResolutionService.class);
    private static final String SOURCE_S3_CACHE = "S3_CACHE";
    private static final String SOURCE_UNDEFINED = "UNDEFINED";

    private final BookSearchService bookSearchService;
    private final BookIdentifierResolver bookIdentifierResolver;
    private final BookDataOrchestrator bookDataOrchestrator;

    /**
     * Creates a canonical cover resolver for controller and application workflows.
     *
     * @param bookSearchService query service for canonical detail projections
     * @param bookIdentifierResolver identifier normalizer for slug/external IDs
     * @param bookDataOrchestrator fallback orchestrator used when projections miss
     */
    public BookCoverResolutionService(BookSearchService bookSearchService,
                                      BookIdentifierResolver bookIdentifierResolver,
                                      BookDataOrchestrator bookDataOrchestrator) {
        this.bookSearchService = bookSearchService;
        this.bookIdentifierResolver = bookIdentifierResolver;
        this.bookDataOrchestrator = bookDataOrchestrator;
    }

    /**
     * Resolves canonical cover payload data for an identifier.
     *
     * @param identifier user-facing identifier (slug, UUID, external ID)
     * @return resolved payload when the book can be found
     */
    public Optional<ResolvedCoverPayload> resolveCover(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            return Optional.empty();
        }

        Optional<ResolvedCoverPayload> fromSlug = bookSearchService.fetchBookDetailBySlug(identifier)
            .map(detail -> toPayload(detail.id(), detail.coverUrl(), detail.thumbnailUrl(), detail.coverWidth(), detail.coverHeight(), detail.coverHighResolution()));
        if (fromSlug.isPresent()) {
            return fromSlug;
        }

        Optional<UUID> maybeUuid = bookIdentifierResolver.resolveToUuid(identifier);
        if (maybeUuid.isPresent()) {
            Optional<ResolvedCoverPayload> fromUuid = bookSearchService.fetchBookDetail(maybeUuid.get())
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
            .onErrorMap(exception -> {
                log.warn("Cover orchestrator lookup failed for '{}': {}", identifier, exception.getMessage());
                return new IllegalStateException("Cover orchestrator lookup failed for identifier '" + identifier + "'", exception);
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

    /**
     * Resolves a canonical UUID for persistence operations.
     *
     * @param identifier request identifier
     * @param resolvedBookId best-known resolved book ID from canonical payload
     * @return canonical UUID when resolvable
     */
    public Optional<UUID> resolveBookUuid(String identifier, String resolvedBookId) {
        UUID fromResolvedId = parseUuidOrNull(resolvedBookId);
        if (fromResolvedId != null) {
            return Optional.of(fromResolvedId);
        }

        UUID fromIdentifier = parseUuidOrNull(identifier);
        if (fromIdentifier != null) {
            return Optional.of(fromIdentifier);
        }

        Optional<UUID> resolvedUuid = bookIdentifierResolver.resolveToUuid(identifier);
        if (resolvedUuid.isPresent()) {
            return resolvedUuid;
        }

        if (!StringUtils.hasText(resolvedBookId)) {
            return Optional.empty();
        }
        return bookIdentifierResolver.resolveToUuid(resolvedBookId);
    }

    private ResolvedCoverPayload toPayload(String bookId,
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

        String fallbackUrl = firstNonBlank(
            fallbackCandidate,
            primaryUrl,
            ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH
        );
        String sourceLabel = resolved.fromS3()
            ? SOURCE_S3_CACHE
            : Optional.ofNullable(UrlSourceDetector.detectSource(resolved.url()))
                .map(Enum::name)
                .orElse(SOURCE_UNDEFINED);

        return new ResolvedCoverPayload(bookId, resolved, fallbackUrl, sourceLabel);
    }

    private UUID parseUuidOrNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException nonUuidIdentifier) {
            log.trace("Identifier '{}' is not a UUID; falling back to slug-based resolution", value, nonUuidIdentifier);
            return null;
        }
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

    /**
     * Immutable projection of canonical cover data used by cover APIs and ingestion.
     *
     * @param bookId canonical book identifier
     * @param cover resolved preferred cover metadata
     * @param fallbackUrl fallback candidate URL for display and relay validation
     * @param sourceLabel detected source label for current resolved cover
     */
    public record ResolvedCoverPayload(String bookId,
                                       CoverUrlResolver.ResolvedCover cover,
                                       String fallbackUrl,
                                       String sourceLabel) {
    }
}
