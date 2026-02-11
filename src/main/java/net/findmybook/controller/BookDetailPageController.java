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
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

/**
 * Serves SEO-enriched HTML shells for individual book pages and redirects
 * ISBN-based URLs to canonical slug-based paths ({@code /book/{slug}}).
 *
 * <p>When a request arrives with a non-canonical identifier (database ID or ISBN),
 * the controller 303-redirects to the slug form before rendering.
 */
@Controller
@Slf4j
public class BookDetailPageController extends SpaShellController {

    private final HomePageSectionsService homePageSectionsService;
    private final int maxDescriptionLength;

    public BookDetailPageController(HomePageSectionsService homePageSectionsService,
                                    BookSeoMetadataService bookSeoMetadataService,
                                    @Value("${app.seo.max-description-length:170}") int maxDescriptionLength) {
        super(bookSeoMetadataService);
        this.homePageSectionsService = homePageSectionsService;
        this.maxDescriptionLength = maxDescriptionLength;
    }

    /** Canonical redirect parameters preserved across slug resolution. */
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

    @GetMapping("/book/{id}")
    public Mono<ResponseEntity<String>> bookDetail(@PathVariable String id, SearchContext search) {
        log.info("Looking up book with ID: {}", id);

        Mono<Book> resolvedBookMono = homePageSectionsService.locateBook(id).cache();

        Mono<ResponseEntity<String>> redirectIfNonCanonical = resolvedBookMono
            .flatMap(book -> {
                if (book == null) {
                    return Mono.empty();
                }
                String canonical = canonicalIdentifier(book);
                if (!StringUtils.hasText(canonical) || canonical.equals(id)) {
                    return Mono.empty();
                }

                UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/book/" + canonical);
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
                        return spaResponse(bookSeoMetadataService.notFoundMetadata("/book/" + id), HttpStatus.NOT_FOUND);
                    }
                    String canonical = canonicalIdentifier(book);
                    BookSeoMetadataService.SeoMetadata metadata = bookSeoMetadataService.bookMetadata(book, maxDescriptionLength);
                    return spaResponse(metadata, HttpStatus.OK);
                })
                .switchIfEmpty(Mono.just(spaResponse(
                    bookSeoMetadataService.notFoundMetadata("/book/" + id),
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

    /** Redirects any ISBN form to the canonical {@code /book/{slug}} path. */
    @GetMapping("/book/isbn/{isbn}")
    public Mono<ResponseEntity<Void>> bookDetailByIsbn(@PathVariable String isbn) {
        return redirectByIsbn(isbn, value -> IsbnUtils.isValidIsbn13(value) || IsbnUtils.isValidIsbn10(value), "invalidIsbn");
    }

    /** Explicit ISBN-13 lookup compatibility endpoint. */
    @GetMapping("/book/isbn13/{isbn13}")
    public Mono<ResponseEntity<Void>> bookDetailByIsbn13(@PathVariable String isbn13) {
        return redirectByIsbn(isbn13, IsbnUtils::isValidIsbn13, "invalidIsbn13");
    }

    /** Explicit ISBN-10 lookup compatibility endpoint. */
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
            .map(book -> book == null ? null : canonicalIdentifier(book))
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

    private static String canonicalIdentifier(Book book) {
        return StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
    }

    private ResponseEntity<Void> redirectTo(String path) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create(path))
            .build();
    }

}
