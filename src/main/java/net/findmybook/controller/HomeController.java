package net.findmybook.controller;

import net.findmybook.dto.BookCard;
import net.findmybook.model.Book;
import net.findmybook.model.image.CoverImageSource;
import net.findmybook.model.image.ImageResolutionPreference;
import net.findmybook.service.AffiliateLinkService;
import net.findmybook.service.BookSeoMetadataService;
import net.findmybook.service.HomePageSectionsService;
import net.findmybook.service.SearchPaginationService;
import net.findmybook.service.EnvironmentService;
import net.findmybook.util.ApplicationConstants;
import net.findmybook.util.IsbnUtils;
import net.findmybook.util.EnumParsingUtils;
import net.findmybook.util.SearchQueryUtils;
import net.findmybook.util.ValidationUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
@Slf4j
public class HomeController {

    private final EnvironmentService environmentService;
    private final AffiliateLinkService affiliateLinkService;
    private final SearchPaginationService searchPaginationService;
    private final HomePageSectionsService homePageSectionsService;
    private final BookSeoMetadataService bookSeoMetadataService;
    private final boolean isYearFilteringEnabled;
    private final boolean spaFrontendEnabled;

    private static final int MAX_RECENT_BOOKS = 8;
    private static final int MAX_BESTSELLERS = 8;
    
    private static final List<String> EXPLORE_QUERIES = List.of(
        "Classic literature", "Modern thrillers", "Space opera adventures", "Historical fiction bestsellers",
        "Award-winning science fiction", "Inspiring biographies", "Mind-bending philosophy", "Beginner's cookbooks",
        "Epic fantasy sagas", "Cyberpunk futures", "Cozy mysteries", "Environmental science",
        "Artificial intelligence ethics", "World mythology", "Travel memoirs"
    );
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    // Regex patterns for ISBN validation
    @Value("${app.seo.max-description-length:170}")
    private int maxDescriptionLength;

    // Affiliate IDs from properties
    /**
     * Constructs the HomeController with required services
     *
     * @param environmentService Service providing environment configuration information
     * @param searchPaginationService Service coordinating paginated search
     */
    public HomeController(EnvironmentService environmentService,
                          HomePageSectionsService homePageSectionsService,
                          BookSeoMetadataService bookSeoMetadataService,
                          @Value("${app.feature.year-filtering.enabled:false}") boolean isYearFilteringEnabled,
                          @Value("${app.frontend.spa.enabled:true}") boolean spaFrontendEnabled,
                          AffiliateLinkService affiliateLinkService,
                          SearchPaginationService searchPaginationService) {
        this.environmentService = environmentService;
        this.homePageSectionsService = homePageSectionsService;
        this.bookSeoMetadataService = bookSeoMetadataService;
        this.isYearFilteringEnabled = isYearFilteringEnabled;
        this.spaFrontendEnabled = spaFrontendEnabled;
        this.affiliateLinkService = affiliateLinkService;
        this.searchPaginationService = searchPaginationService;
    }

    private void applyBaseAttributes(Model model, String activeTab) {
        model.addAttribute("isDevelopmentMode", environmentService.isDevelopmentMode());
        model.addAttribute("currentEnv", environmentService.getCurrentEnvironmentMode());
        model.addAttribute("activeTab", activeTab);
    }

    /**
     * Determines the active navigation tab based on the source parameter
     * @param source The source parameter from navigation (explore, categories, or null)
     * @return The active tab name for navigation highlighting
     */
    private String determineActiveTab(String source) {
        if ("explore".equals(source)) {
            return "explore";
        }
        if ("categories".equals(source)) {
            return "categories";
        }
        return "search";
    }

    /** Renders the homepage with bestsellers and recently viewed sections. */
    @GetMapping("/")
    public Mono<String> home(Model model) {
        applyBaseAttributes(model, "home");
        bookSeoMetadataService.apply(model, bookSeoMetadataService.homeMetadata());

        if (spaFrontendEnabled) {
            return Mono.just("spa/index");
        }

        // Set default empty collections for immediate rendering
        model.addAttribute("currentBestsellers", List.<BookCard>of());
        model.addAttribute("recentBooks", List.<BookCard>of());

        Mono<List<BookCard>> bestsellers = homePageSectionsService.loadCurrentBestsellers(MAX_BESTSELLERS)
            .timeout(Duration.ofMillis(3000))
            .onErrorMap(e -> {
                if (e instanceof java.util.concurrent.TimeoutException) {
                    log.warn("Bestsellers timed out after 3000ms");
                } else {
                    log.warn("Bestsellers failed: {}", e.getMessage());
                }
                return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Homepage bestsellers load failed",
                    e
                );
            })
            .doOnNext(list -> {
                model.addAttribute("currentBestsellers", list);
                if (!list.isEmpty()) {
                    log.debug("Homepage: Loaded {} bestsellers successfully", list.size());
                }
            });

        Mono<List<BookCard>> recentBooks = homePageSectionsService.loadRecentBooks(MAX_RECENT_BOOKS)
            .timeout(Duration.ofMillis(3000))
            .onErrorMap(e -> {
                if (e instanceof java.util.concurrent.TimeoutException) {
                    log.warn("Recent books timed out after 3000ms");
                } else {
                    log.warn("Recent books failed: {}", e.getMessage());
                }
                return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Homepage recent books load failed",
                    e
                );
            })
            .doOnNext(list -> {
                model.addAttribute("recentBooks", list);
                if (!list.isEmpty()) {
                    log.debug("Homepage: Loaded {} recent books successfully", list.size());
                }
            });

        return Mono.zipDelayError(bestsellers, recentBooks)
            .then(Mono.just("index"))
            .onErrorMap(e -> {
                log.error("Critical error loading homepage sections: {}", e.getMessage(), e);
                return new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Critical error loading homepage sections",
                    e
                );
            });
    }

    private void applyBookMetadata(Book book, Model model) {
        model.addAttribute("book", book);
        bookSeoMetadataService.apply(model, bookSeoMetadataService.bookMetadata(book, maxDescriptionLength));
        model.addAttribute("affiliateLinks", affiliateLinkService.generateLinks(book));
        homePageSectionsService.recordRecentlyViewed(book);
    }

    /** Renders the search page and server-side initial results window when query is present. */
    @GetMapping("/search")
    public Mono<String> search(@RequestParam(required = false) String query,
                               @RequestParam(required = false) Integer year,
                               @RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false, defaultValue = "newest") String sort,
                               @RequestParam(required = false) String source,
                               @RequestParam(required = false, defaultValue = "ANY") String coverSource,
                               @RequestParam(required = false, defaultValue = "ANY") String resolution,
                               Model model) {
        // Handle year extraction from query if enabled
        if (isYearFilteringEnabled && year == null && ValidationUtils.hasText(query)) {
            Matcher matcher = YEAR_PATTERN.matcher(query);
            if (matcher.find()) {
                int extractedYear = Integer.parseInt(matcher.group(1));
                log.info("Detected year {} in query text. Redirecting to use year parameter.", extractedYear);

                String processedQuery = (query.substring(0, matcher.start()) + query.substring(matcher.end()))
                    .trim()
                    .replaceAll("\\s+", " ");

                UriComponentsBuilder redirectBuilder = UriComponentsBuilder.fromPath("/search")
                    .queryParamIfPresent("query", ValidationUtils.hasText(processedQuery) ? Optional.of(processedQuery) : Optional.empty())
                    .queryParam("year", extractedYear);

                if (ValidationUtils.hasText(sort)) {
                    redirectBuilder.queryParam("sort", sort);
                }
                if (ValidationUtils.hasText(source)) {
                    redirectBuilder.queryParam("source", source);
                }
                if (ValidationUtils.hasText(coverSource)) {
                    redirectBuilder.queryParam("coverSource", coverSource);
                }
                if (ValidationUtils.hasText(resolution)) {
                    redirectBuilder.queryParam("resolution", resolution);
                }

                String redirectPath = redirectBuilder.build().encode(StandardCharsets.UTF_8).toUriString();
                return Mono.just("redirect:" + redirectPath);
            }
        }

        Integer effectiveYear = isYearFilteringEnabled ? year : null;
        CoverImageSource coverSourcePreference = EnumParsingUtils.parseOrDefault(
            coverSource,
            CoverImageSource.class,
            CoverImageSource.ANY,
            raw -> log.debug("Invalid coverSource '{}' on /search view request, defaulting to ANY", raw)
        );
        ImageResolutionPreference resolutionPreference = EnumParsingUtils.parseOrDefault(
            resolution,
            ImageResolutionPreference.class,
            ImageResolutionPreference.ANY,
            raw -> log.debug("Invalid resolution '{}' on /search view request, defaulting to ANY", raw)
        );

        String activeTab = determineActiveTab(source);
        applyBaseAttributes(model, activeTab);
        model.addAttribute("query", query);
        model.addAttribute("year", effectiveYear);
        model.addAttribute("currentPage", page);
        model.addAttribute("currentSort", sort);
        model.addAttribute("source", source);
        model.addAttribute("coverSource", coverSourcePreference.name());
        model.addAttribute("resolution", resolutionPreference.name());
        model.addAttribute("isYearFilteringEnabled", isYearFilteringEnabled);

        bookSeoMetadataService.apply(model, bookSeoMetadataService.searchMetadata());

        if (spaFrontendEnabled) {
            return Mono.just("spa/index");
        }

        // Server-render initial results if query provided
        if (ValidationUtils.hasText(query)) {
            final int pageSize = ApplicationConstants.Paging.DEFAULT_SEARCH_LIMIT;
            final int safePage = Math.max(page, 0);
            final int startIndex = Math.multiplyExact(safePage, pageSize);
            String normalizedQuery = SearchQueryUtils.normalize(query);

            SearchPaginationService.SearchRequest request = new SearchPaginationService.SearchRequest(
                normalizedQuery,
                startIndex,
                pageSize,
                sort,
                coverSourcePreference,
                resolutionPreference,
                effectiveYear
            );

            return searchPaginationService.search(request)
                .map(result -> {
                    List<Book> windowed = result.pageItems();
                    model.addAttribute("initialResults", windowed);
                    model.addAttribute("hasInitialResults", !windowed.isEmpty());
                    model.addAttribute("totalResults", result.totalUnique());
                    model.addAttribute("initialResultsError", null);
                    log.info("Server-rendered {} search results for query '{}'", windowed.size(), query);
                    return "search";
                })
                .onErrorMap(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Timeout server-rendering search results for '{}': {}", query, e.getMessage());
                    } else {
                        log.warn("Error server-rendering search results for '{}': {}", query, e.getMessage(), e);
                    }
                    return new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Server-rendered search failed for query: " + query,
                        e
                    );
                });
        }

        // No query - show search page with no results
        model.addAttribute("initialResults", List.of());
        model.addAttribute("hasInitialResults", false);
        return Mono.just("search");
    }

    /** Renders the detail page for a book identifier and redirects to canonical slug when needed. */
    @GetMapping("/book/{id}")
    public Mono<String> bookDetail(@PathVariable String id,
                             @RequestParam(required = false) String query,
                             @RequestParam(required = false, defaultValue = "0") int page,
                             @RequestParam(required = false, defaultValue = "relevance") String sort,
                             @RequestParam(required = false, defaultValue = "grid") String view,
                             Model model) {
        log.info("Looking up book with ID: {}", id);

        applyBaseAttributes(model, "book");
        model.addAttribute("searchQuery", query);
        model.addAttribute("searchPage", page);
        model.addAttribute("searchSort", sort);
        model.addAttribute("searchView", view);

        bookSeoMetadataService.apply(model, bookSeoMetadataService.bookFallbackMetadata(id));

        Mono<Book> canonicalBookMono = homePageSectionsService.locateBook(id).cache();
        Mono<Book> effectiveBookMono = canonicalBookMono;

        Mono<String> redirectIfNonCanonical = effectiveBookMono
            .flatMap(book -> {
                if (book == null) {
                    return Mono.empty();
                }
                String canonicalIdentifier = ValidationUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId();
                if (!ValidationUtils.hasText(canonicalIdentifier) || canonicalIdentifier.equals(id)) {
                    return Mono.empty();
                }
                var builder = UriComponentsBuilder.fromPath("/book/" + canonicalIdentifier);
                if (ValidationUtils.hasText(query)) {
                    builder.queryParam("query", query);
                }
                if (page > 0) {
                    builder.queryParam("page", page);
                }
                if (ValidationUtils.hasText(sort)) {
                    builder.queryParam("sort", sort);
                }
                if (ValidationUtils.hasText(view)) {
                    builder.queryParam("view", view);
                }
                return Mono.just("redirect:" + builder.build().toUriString());
            });

        // Only display books that exist in our database
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

        // Load similar books in parallel with increased timeout to allow Postgres queries to complete
        Mono<List<Book>> similarBooksMono = resolvedBookMono
            .flatMap(book -> homePageSectionsService.loadSimilarBooks(
                    book != null ? book.getId() : id,
                    ApplicationConstants.Paging.DEFAULT_TIERED_LIMIT / 2,
                    6
                )
                .timeout(Duration.ofMillis(2000)) // Increased from 300ms to 2000ms
                .onErrorMap(e -> {
                    if (e instanceof java.util.concurrent.TimeoutException) {
                        log.warn("Similar books timed out after 2000ms for {}", id);
                    } else {
                        log.warn("Similar books failed for {}: {}", id, e.getMessage());
                    }
                    return new IllegalStateException("Similar books load failed for " + id, e);
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
    
    private Mono<ResponseEntity<Void>> redirectByIsbn(String rawIsbn,
                                                      Predicate<String> validator,
                                                      String errorCode) {
        String sanitized = IsbnUtils.sanitize(rawIsbn);
        if (!ValidationUtils.hasText(sanitized) || !validator.test(sanitized)) {
            log.warn("Invalid ISBN format: {}", rawIsbn);
            return Mono.just(redirectTo(String.format("/?error=%s&originalIsbn=%s", errorCode, rawIsbn)));
        }
        Mono<Book> lookupMono = homePageSectionsService.locateBook(sanitized);

        return lookupMono
            .map(book -> book == null ? null : (ValidationUtils.hasText(book.getSlug()) ? book.getSlug() : book.getId()))
            .filter(ValidationUtils::hasText)
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

    /** Redirects `/explore` to search with a random curated query. */
    @GetMapping("/explore")
    public ResponseEntity<Void> explore() {
        String selectedQuery = EXPLORE_QUERIES.get(ThreadLocalRandom.current().nextInt(EXPLORE_QUERIES.size()));
        log.info("Explore page requested, redirecting to search with query: '{}'", selectedQuery);
        String encodedQuery = URLEncoder.encode(selectedQuery, StandardCharsets.UTF_8);
        return redirectTo("/search?query=" + encodedQuery + "&source=explore");
    }

    /** Redirects `/categories` to search with a random curated query. */
    @GetMapping("/categories")
    public ResponseEntity<Void> categories() {
        String selectedQuery = EXPLORE_QUERIES.get(ThreadLocalRandom.current().nextInt(EXPLORE_QUERIES.size()));
        log.info("Categories page requested, redirecting to search with query: '{}'", selectedQuery);
        String encodedQuery = URLEncoder.encode(selectedQuery, StandardCharsets.UTF_8);
        return redirectTo("/search?query=" + encodedQuery + "&source=categories");
    }
}
