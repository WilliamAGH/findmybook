package net.findmybook.controller;

import java.net.URI;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import net.findmybook.model.Book;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.util.IsbnUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Page controller for book detail shell rendering and ISBN redirect endpoints.
 */
@Controller
@Slf4j
public class BookDetailPageController {

    private final HomePageSectionsService homePageSectionsService;
    private final BookSeoMetadataService bookSeoMetadataService;
    private final int maxDescriptionLength;

    public BookDetailPageController(HomePageSectionsService homePageSectionsService,
                                    BookSeoMetadataService bookSeoMetadataService,
                                    @Value("${app.seo.max-description-length:170}") int maxDescriptionLength) {
        this.homePageSectionsService = homePageSectionsService;
        this.bookSeoMetadataService = bookSeoMetadataService;
        this.maxDescriptionLength = maxDescriptionLength;
    }

    /**
     * Groups optional search-context query parameters for canonical redirect construction.
     */
    record SearchContext(String query, Integer page, String orderBy, String view) {
        int effectivePage() {
            return page != null ? page : 0;
        }

        String effectiveOrderBy() {
            return orderBy != null && !orderBy.isBlank() ? orderBy : "newest";
        }

        String effectiveView() {
            return view != null && !view.isBlank() ? view : "grid";
        }
    }

    /**
     * Renders the detail SPA shell for a book identifier and redirects to canonical slug when needed.
     */
    @GetMapping("/book/{id}")
    public Mono<ResponseEntity<String>> bookDetail(@PathVariable String id, SearchContext search) {
        log.info("Looking up book with ID: {}", id);

        Mono<Book> resolvedBookMono = homePageSectionsService.locateBook(id).cache();

        Mono<ResponseEntity<String>> redirectIfNonCanonical = resolvedBookMono
            .flatMap(book -> {
                if (book == null) {
                    return Mono.empty();
                }
                String canonicalIdentifier = StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
                if (!StringUtils.hasText(canonicalIdentifier) || canonicalIdentifier.equals(id)) {
                    return Mono.empty();
                }

                UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/book/" + canonicalIdentifier);
                if (StringUtils.hasText(search.query())) {
                    builder.queryParam("query", search.query());
                }
                if (search.effectivePage() > 0) {
                    builder.queryParam("page", search.effectivePage());
                }
                if (StringUtils.hasText(search.effectiveOrderBy())) {
                    builder.queryParam("orderBy", search.effectiveOrderBy());
                }
                if (StringUtils.hasText(search.effectiveView())) {
                    builder.queryParam("view", search.effectiveView());
                }
                String redirectPath = builder.build().toUriString();
                return Mono.just(ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .location(URI.create(redirectPath))
                    .build());
            });

        return redirectIfNonCanonical.switchIfEmpty(
            resolvedBookMono
                .map(book -> {
                    if (book == null) {
                        return spaResponse(bookSeoMetadataService.notFoundMetadata("/book/" + id), "/book/" + id, HttpStatus.NOT_FOUND);
                    }
                    String canonicalIdentifier = StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
                    homePageSectionsService.recordRecentlyViewed(book);
                    BookSeoMetadataService.SeoMetadata metadata = bookSeoMetadataService.bookMetadata(book, maxDescriptionLength);
                    return spaResponse(metadata, "/book/" + canonicalIdentifier, HttpStatus.OK);
                })
                .switchIfEmpty(Mono.just(spaResponse(
                    bookSeoMetadataService.notFoundMetadata("/book/" + id),
                    "/book/" + id,
                    HttpStatus.NOT_FOUND
                )))
        ).onErrorMap(e -> {
            log.error("Error preparing book detail shell for {}: {}", id, e.getMessage(), e);
            return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Book detail shell rendering failed for " + id,
                e
            );
        });
    }

    /**
     * Redirects ISBN requests to canonical `/book/{slugOrId}` URLs when possible.
     */
    @GetMapping("/book/isbn/{isbn}")
    public Mono<ResponseEntity<Void>> bookDetailByIsbn(@PathVariable String isbn) {
        return redirectByIsbn(isbn, value -> IsbnUtils.isValidIsbn13(value) || IsbnUtils.isValidIsbn10(value), "invalidIsbn");
    }

    /**
     * Compatibility endpoint for explicit ISBN-13 lookup.
     */
    @GetMapping("/book/isbn13/{isbn13}")
    public Mono<ResponseEntity<Void>> bookDetailByIsbn13(@PathVariable String isbn13) {
        return redirectByIsbn(isbn13, IsbnUtils::isValidIsbn13, "invalidIsbn13");
    }

    /**
     * Compatibility endpoint for explicit ISBN-10 lookup.
     */
    @GetMapping("/book/isbn10/{isbn10}")
    public Mono<ResponseEntity<Void>> bookDetailByIsbn10(@PathVariable String isbn10) {
        return redirectByIsbn(isbn10, IsbnUtils::isValidIsbn10, "invalidIsbn10");
    }

    private Mono<ResponseEntity<Void>> redirectByIsbn(String rawIsbn,
                                                      Predicate<String> validator,
                                                      String errorCode) {
        String sanitized = IsbnUtils.sanitize(rawIsbn);
        if (!StringUtils.hasText(sanitized) || !validator.test(sanitized)) {
            log.warn("Invalid ISBN format: {}", rawIsbn);
            return Mono.just(redirectTo(String.format("/?error=%s&originalIsbn=%s", errorCode, rawIsbn)));
        }
        Mono<Book> lookupMono = homePageSectionsService.locateBook(sanitized);

        return lookupMono
            .map(book -> book == null ? null : (StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId()))
            .filter(StringUtils::hasText)
            .map(target -> redirectTo("/book/" + target))
            .switchIfEmpty(Mono.fromSupplier(() -> redirectTo("/?info=bookNotFound&isbn=" + sanitized)))
            .onErrorMap(e -> {
                log.error("Error during ISBN lookup for {}: {}", rawIsbn, e.getMessage(), e);
                return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "ISBN redirect lookup failed for " + rawIsbn,
                    e
                );
            });
    }

    private ResponseEntity<Void> redirectTo(String path) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create(path))
            .build();
    }

    private ResponseEntity<String> spaResponse(BookSeoMetadataService.SeoMetadata metadata,
                                               String requestPath,
                                               HttpStatus status) {
        String html = bookSeoMetadataService.renderSpaShell(metadata, requestPath, status.value());
        return ResponseEntity.status(status)
            .contentType(MediaType.TEXT_HTML)
            .body(html);
    }
}
