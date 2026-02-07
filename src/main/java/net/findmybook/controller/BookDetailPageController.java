package net.findmybook.controller;

import net.findmybook.model.Book;
import net.findmybook.service.AffiliateLinkService;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.EnvironmentService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.IsbnUtils;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * Page controller for book detail views and ISBN redirect endpoints.
 *
 * <p>Extracted from {@link HomeController} to keep each controller under the LOC ceiling.
 * Owns the {@code /book/**} URL space for server-rendered (Thymeleaf) and SPA views.</p>
 */
@Controller
@Slf4j
public class BookDetailPageController {

    private final EnvironmentService environmentService;
    private final AffiliateLinkService affiliateLinkService;
    private final HomePageSectionsService homePageSectionsService;
    private final BookSeoMetadataService bookSeoMetadataService;
    private final boolean spaFrontendEnabled;
    private final int maxDescriptionLength;

    public BookDetailPageController(EnvironmentService environmentService,
                                    AffiliateLinkService affiliateLinkService,
                                    HomePageSectionsService homePageSectionsService,
                                    BookSeoMetadataService bookSeoMetadataService,
                                    @Value("${app.frontend.spa.enabled:true}") boolean spaFrontendEnabled,
                                    @Value("${app.seo.max-description-length:170}") int maxDescriptionLength) {
        this.environmentService = environmentService;
        this.affiliateLinkService = affiliateLinkService;
        this.homePageSectionsService = homePageSectionsService;
        this.bookSeoMetadataService = bookSeoMetadataService;
        this.spaFrontendEnabled = spaFrontendEnabled;
        this.maxDescriptionLength = maxDescriptionLength;
    }

    /**
     * Groups the optional search-context query parameters that travel together
     * across book detail requests and redirect URL building.
     */
    record SearchContext(String query, Integer page, String sort, String view) {
        int effectivePage() { return page != null ? page : 0; }
        String effectiveSort() { return sort != null && !sort.isBlank() ? sort : "relevance"; }
        String effectiveView() { return view != null && !view.isBlank() ? view : "grid"; }
    }

    /** Renders the detail page for a book identifier and redirects to canonical slug when needed. */
    @GetMapping("/book/{id}")
    public Mono<String> bookDetail(@PathVariable String id,
                                   SearchContext search,
                                   Model model) {
        log.info("Looking up book with ID: {}", id);

        applyBaseAttributes(model, "book");
        model.addAttribute("searchQuery", search.query());
        model.addAttribute("searchPage", search.effectivePage());
        model.addAttribute("searchSort", search.effectiveSort());
        model.addAttribute("searchView", search.effectiveView());

        bookSeoMetadataService.apply(model, bookSeoMetadataService.bookFallbackMetadata(id));

        Mono<Book> canonicalBookMono = homePageSectionsService.locateBook(id).cache();
        Mono<Book> effectiveBookMono = canonicalBookMono;

        Mono<String> redirectIfNonCanonical = effectiveBookMono
            .flatMap(book -> {
                if (book == null) {
                    return Mono.empty();
                }
                String canonicalIdentifier = StringUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
                if (!StringUtils.hasText(canonicalIdentifier) || canonicalIdentifier.equals(id)) {
                    return Mono.empty();
                }
                var builder = UriComponentsBuilder.fromPath("/book/" + canonicalIdentifier);
                if (StringUtils.hasText(search.query())) {
                    builder.queryParam("query", search.query());
                }
                if (search.effectivePage() > 0) {
                    builder.queryParam("page", search.effectivePage());
                }
                if (StringUtils.hasText(search.effectiveSort())) {
                    builder.queryParam("sort", search.effectiveSort());
                }
                if (StringUtils.hasText(search.effectiveView())) {
                    builder.queryParam("view", search.effectiveView());
                }
                return Mono.just("redirect:" + builder.build().toUriString());
            });

        Mono<Book> resolvedBookMono = effectiveBookMono.cache();

        if (spaFrontendEnabled) {
            return redirectIfNonCanonical.switchIfEmpty(
                resolvedBookMono.flatMap(book -> {
                    if (book != null) {
                        applyBookMetadata(book, model);
                    }
                    return Mono.just("spa/index");
                }).switchIfEmpty(Mono.just("spa/index"))
            ).onErrorMap(e -> {
                log.error("Error preparing SPA book detail for {}: {}", id, e.getMessage(), e);
                return new IllegalStateException("Error preparing SPA book detail for " + id, e);
            });
        }

        // Load similar books in parallel
        Mono<List<Book>> similarBooksMono = resolvedBookMono
            .flatMap(book -> homePageSectionsService.loadSimilarBooks(
                    book != null ? book.getId() : id,
                    ApplicationConstants.Paging.DEFAULT_TIERED_LIMIT / 2,
                    6
                )
                .timeout(Duration.ofMillis(2000))
                .onErrorResume(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Similar books timed out after 2000ms for {}", id);
                    } else {
                        log.warn("Similar books failed for {}: {}", id, e.getMessage());
                    }
                    return Mono.empty();
                }))
            .defaultIfEmpty(List.<Book>of())
            .doOnNext(list -> model.addAttribute("similarBooks", list))
            .cache();

        return redirectIfNonCanonical.switchIfEmpty(
            resolvedBookMono.flatMap(book -> {
                if (book == null) {
                    model.addAttribute("book", null);
                    model.addAttribute("error", "Unable to locate this book right now. Please try again later.");
                    return similarBooksMono.thenReturn("book");
                }

                applyBookMetadata(book, model);
                return similarBooksMono.thenReturn("book");
            }).switchIfEmpty(similarBooksMono.thenReturn("book"))
        ).onErrorMap(e -> {
            log.error("Error rendering book detail for {}: {}", id, e.getMessage(), e);
            return new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Book detail rendering failed for " + id,
                e
            );
        });
    }

    /** Redirects ISBN requests to canonical `/book/{slugOrId}` URLs when possible. */
    @GetMapping("/book/isbn/{isbn}")
    public Mono<ResponseEntity<Void>> bookDetailByIsbn(@PathVariable String isbn) {
        return redirectByIsbn(isbn, value -> IsbnUtils.isValidIsbn13(value) || IsbnUtils.isValidIsbn10(value), "invalidIsbn");
    }

    /** Compatibility endpoint for explicit ISBN-13 lookup. */
    @GetMapping("/book/isbn13/{isbn13}")
    public Mono<ResponseEntity<Void>> bookDetailByIsbn13(@PathVariable String isbn13) {
        return redirectByIsbn(isbn13, IsbnUtils::isValidIsbn13, "invalidIsbn13");
    }

    /** Compatibility endpoint for explicit ISBN-10 lookup. */
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

    private void applyBaseAttributes(Model model, String activeTab) {
        model.addAttribute("isDevelopmentMode", environmentService.isDevelopmentMode());
        model.addAttribute("currentEnv", environmentService.getCurrentEnvironmentMode());
        model.addAttribute("activeTab", activeTab);
    }

    private void applyBookMetadata(Book book, Model model) {
        model.addAttribute("book", book);
        bookSeoMetadataService.apply(model, bookSeoMetadataService.bookMetadata(book, maxDescriptionLength));
        model.addAttribute("affiliateLinks", affiliateLinkService.generateLinks(book));
        homePageSectionsService.recordRecentlyViewed(book);
    }

    private ResponseEntity<Void> redirectTo(String path) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create(path))
            .build();
    }
}
