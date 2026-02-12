package net.findmybook.controller;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import java.util.NoSuchElementException;
import net.findmybook.application.cover.BookCoverResolutionService;
import net.findmybook.application.cover.BrowserCoverIngestUseCase;
import net.findmybook.application.cover.BrowserCoverIngestUseCase.BrowserCoverIngestResult;
import net.findmybook.application.cover.BrowserCoverIngestUseCase.CoverImageRejectedException;
import net.findmybook.exception.CoverTooLargeException;
import net.findmybook.exception.S3UploadException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public cover API endpoints for read and browser-ingest workflows.
 *
 * <p>This controller stays HTTP-focused and delegates canonical resolution and ingest
 * orchestration to application services.</p>
 */
@RestController
@RequestMapping("/api/covers")
@Slf4j
public class BookCoverController {

    private final BookCoverResolutionService bookCoverResolutionService;
    private final BrowserCoverIngestUseCase browserCoverIngestUseCase;

    /**
     * Creates the cover API controller.
     *
     * @param bookCoverResolutionService canonical cover resolver used by GET endpoint
     * @param browserCoverIngestUseCase browser relay ingestion use case for POST endpoint
     */
    public BookCoverController(BookCoverResolutionService bookCoverResolutionService,
                               BrowserCoverIngestUseCase browserCoverIngestUseCase) {
        this.bookCoverResolutionService = bookCoverResolutionService;
        this.browserCoverIngestUseCase = browserCoverIngestUseCase;
    }

    /**
     * Returns canonical cover metadata for a user-facing identifier.
     *
     * @param identifier slug/UUID/external identifier
     * @param sourcePreference requested source hint
     * @return canonical cover response
     */
    @GetMapping("/{identifier}")
    public ResponseEntity<CoverResponse> getCover(@PathVariable String identifier,
                                                  @RequestParam(required = false, defaultValue = "ANY") String sourcePreference) {
        try {
            BookCoverResolutionService.ResolvedCoverPayload payload = bookCoverResolutionService.resolveCover(identifier)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found with ID: " + identifier));

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
        } catch (IllegalStateException exception) {
            log.error("Cover resolution failed for '{}': {}", identifier, exception.getMessage(), exception);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Cover resolution failed for '" + identifier + "'",
                exception
            );
        }
    }

    /**
     * Persists a browser-fetched cover payload to S3 and canonical metadata storage.
     *
     * @param identifier user-facing book identifier
     * @param image uploaded image bytes from the browser
     * @param sourceUrl external URL used by browser fetch
     * @param source optional provider hint
     * @return persisted cover metadata response
     */
    @PostMapping(path = "/{identifier}/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimiter(name = "coverRelayRateLimiter")
    public ResponseEntity<CoverIngestResponse> ingestCoverFromBrowser(@PathVariable String identifier,
                                                                       @RequestParam("image") MultipartFile image,
                                                                       @RequestParam("sourceUrl") String sourceUrl,
                                                                       @RequestParam(required = false) String source) {
        try {
            BrowserCoverIngestResult result = browserCoverIngestUseCase.ingest(identifier, image, sourceUrl, source);
            return ResponseEntity.ok(new CoverIngestResponse(
                result.bookId(),
                result.storedCoverUrl(),
                result.storageKey(),
                result.source(),
                result.width(),
                result.height(),
                result.highResolution()
            ));
        } catch (NoSuchElementException exception) {
            log.warn("Cover ingest target not found for identifier '{}' (sourceUrl='{}')", identifier, sourceUrl, exception);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Book cover target not found.", exception);
        } catch (CoverImageRejectedException exception) {
            log.warn("Cover ingest rejected for identifier '{}' (sourceUrl='{}'): {}", identifier, sourceUrl, exception.getMessage());
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Uploaded cover image failed validation.", exception);
        } catch (IllegalArgumentException exception) {
            log.warn("Invalid cover ingest request for identifier '{}' (sourceUrl='{}'): {}", identifier, sourceUrl, exception.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cover ingest request.", exception);
        } catch (CoverTooLargeException exception) {
            log.warn("Cover ingest payload too large for identifier '{}' (sourceUrl='{}')", identifier, sourceUrl, exception);
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, "Uploaded cover image exceeds size limits.", exception);
        } catch (S3UploadException exception) {
            log.error("Cover ingest storage failure for identifier '{}' (sourceUrl='{}')", identifier, sourceUrl, exception);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cover storage service is temporarily unavailable.", exception);
        } catch (IllegalStateException exception) {
            log.error("Cover ingest internal failure for identifier '{}' (sourceUrl='{}')", identifier, sourceUrl, exception);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cover ingest failed unexpectedly.", exception);
        }
    }

    /**
     * Typed cover metadata nested in the public GET response.
     */
    record CoverDetail(String preferredUrl,
                       String fallbackUrl,
                       String source,
                       Integer width,
                       Integer height,
                       Boolean highResolution,
                       String requestedSourcePreference) {
    }

    /**
     * Public response contract for GET cover metadata.
     */
    record CoverResponse(String bookId,
                         String requestedSourcePreference,
                         CoverDetail cover,
                         String coverUrl,
                         String preferredUrl,
                         String fallbackUrl) {
    }

    /**
     * Public response contract for browser relay ingestion.
     */
    record CoverIngestResponse(String bookId,
                               String storedCoverUrl,
                               String storageKey,
                               String source,
                               Integer width,
                               Integer height,
                               Boolean highResolution) {
    }
}
