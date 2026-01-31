package net.findmybook.controller;

import net.findmybook.repository.BookQueryRepository;
import net.findmybook.service.BookDataOrchestrator;
import net.findmybook.service.BookIdentifierResolver;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.ValidationUtils;
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
import java.util.HashMap;
import java.util.Map;
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

    private final BookQueryRepository bookQueryRepository;
    private final BookIdentifierResolver bookIdentifierResolver;
    private final BookDataOrchestrator bookDataOrchestrator;

    public BookCoverController(BookQueryRepository bookQueryRepository,
                               BookIdentifierResolver bookIdentifierResolver,
                               BookDataOrchestrator bookDataOrchestrator) {
        this.bookQueryRepository = bookQueryRepository;
        this.bookIdentifierResolver = bookIdentifierResolver;
        this.bookDataOrchestrator = bookDataOrchestrator;
    }

    @GetMapping("/{identifier}")
    public ResponseEntity<Map<String, Object>> getCover(@PathVariable String identifier,
                                                        @RequestParam(required = false, defaultValue = "ANY") String sourcePreference) {
        CoverPayload payload = resolveCover(identifier)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found with ID: " + identifier));

        Map<String, Object> response = new HashMap<>();
        response.put("bookId", payload.bookId());
        response.put("requestedSourcePreference", sourcePreference);

        Map<String, Object> cover = new HashMap<>();
        cover.put("preferredUrl", payload.cover().url());
        cover.put("fallbackUrl", payload.fallbackUrl());
        cover.put("source", payload.sourceLabel());
        cover.put("width", payload.cover().width());
        cover.put("height", payload.cover().height());
        cover.put("highResolution", payload.cover().highResolution());
        cover.put("requestedSourcePreference", sourcePreference);
        response.put("cover", cover);

        response.put("coverUrl", payload.cover().url());
        response.put("preferredUrl", payload.cover().url());
        response.put("fallbackUrl", payload.fallbackUrl());

        return ResponseEntity.ok(response);
    }

    private Optional<CoverPayload> resolveCover(String identifier) {
        if (!ValidationUtils.hasText(identifier)) {
            return Optional.empty();
        }

        Optional<CoverPayload> fromSlug = bookQueryRepository.fetchBookDetailBySlug(identifier)
            .map(detail -> toPayload(detail.id(), detail.coverUrl(), detail.thumbnailUrl(), detail.coverWidth(), detail.coverHeight(), detail.coverHighResolution()));
        if (fromSlug.isPresent()) {
            return fromSlug;
        }

        Optional<UUID> maybeUuid = bookIdentifierResolver.resolveToUuid(identifier);
        if (maybeUuid.isPresent()) {
            Optional<CoverPayload> fromUuid = bookQueryRepository.fetchBookDetail(maybeUuid.get())
                .map(detail -> toPayload(detail.id(), detail.coverUrl(), detail.thumbnailUrl(), detail.coverWidth(), detail.coverHeight(), detail.coverHighResolution()));
            if (fromUuid.isPresent()) {
                return fromUuid;
            }
        }

        if (bookDataOrchestrator == null) {
            return Optional.empty();
        }

        try {
            return bookDataOrchestrator.fetchCanonicalBookReactive(identifier)
                .timeout(Duration.ofSeconds(5))
                .blockOptional()
                .map(book -> toPayload(
                    book.getId(),
                    book.getS3ImagePath(),
                    book.getExternalImageUrl(),
                    book.getCoverImageWidth(),
                    book.getCoverImageHeight(),
                    book.getIsCoverHighResolution()
                ));
        } catch (Exception ex) {
            log.warn("Cover orchestrator fallback failed for '{}': {}", identifier, ex.getMessage());
            return Optional.empty();
        }
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
            ? "S3_CACHE"
            : Optional.ofNullable(UrlSourceDetector.detectSource(resolved.url()))
                .map(Enum::name)
                .orElse("UNDEFINED");

        return new CoverPayload(bookId, resolved, fallbackUrl, sourceLabel);
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH;
        }
        for (String candidate : candidates) {
            if (ValidationUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return ApplicationConstants.Cover.PLACEHOLDER_IMAGE_PATH;
    }

    private record CoverPayload(String bookId,
                                CoverUrlResolver.ResolvedCover cover,
                                String fallbackUrl,
                                String sourceLabel) {}
}
